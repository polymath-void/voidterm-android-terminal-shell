package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    private var drawingThread: Thread? = null
    @Volatile
    private var isRunning = false

    private val ansiParser = AnsiParser()
    private val terminalLines = mutableListOf<List<AnsiParser.StyledText>>()

    // Active terminal configuration
    var config: TerminalConfig = TerminalConfig(context)
        private set

    // Dynamic prompt state
    var currentWorkingDirectory: String = "~"
        private set
    var promptText: String = "void:~$ "
        private set

    var onCwdPollRequested: (() -> Unit)? = null
    var onUrlClicked: ((String) -> Unit)? = null
    var onDimensionsChanged: ((rows: Int, cols: Int) -> Unit)? = null
    val detectedLinks = mutableListOf<LinkDetector.DetectedLink>()

    // Sticky modifier keys
    var isCtrlActive: Boolean = false
        set(value) {
            field = value
            onModifierStateChanged?.invoke()
        }
    var isAltActive: Boolean = false
        set(value) {
            field = value
            onModifierStateChanged?.invoke()
        }
    var isShiftActive: Boolean = false
        set(value) {
            field = value
            onModifierStateChanged?.invoke()
        }
    var onModifierStateChanged: (() -> Unit)? = null

    // Text Selection Mode & Marker Handles
    var isSelectionMode: Boolean = false
        private set
    var selectionStart: Int = -1
        private set
    var selectionEnd: Int = -1
        private set
    var onSelectionChanged: ((Boolean, String?) -> Unit)? = null

    private enum class Handle { NONE, START, END }
    private var activeHandle: Handle = Handle.NONE

    private var startHandleX: Float = 0f
    private var startHandleY: Float = 0f
    private var endHandleX: Float = 0f
    private var endHandleY: Float = 0f

    @Volatile private var cachedLayout: StaticLayout? = null
    @Volatile private var cachedSpannableText: String = ""

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50FA7B")
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181825")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Active inline input state
    var currentInputBuffer: String = ""
    var cursorPosition: Int = 0
    var onCommandSubmitted: ((String) -> Unit)? = null

    // Command history
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    // Touch and Scroll tracking
    private var currentScrollOffset = 0f
    private var maxScrollOffset = 0f
    private var isUserScrolled = false
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val backgroundPaint = Paint().apply {
        color = config.theme.background
    }

    private var currentTextSize = config.fontSize
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = currentTextSize
        color = config.theme.foreground
    }

    private var isScaling = false
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            currentTextSize = (currentTextSize * scaleFactor).coerceIn(20f, 100f)
            config.fontSize = currentTextSize
            textPaint.textSize = currentTextSize
            ansiParser.applyTheme(config.theme, currentTextSize)
            triggerRender()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val layout = cachedLayout ?: return
            val text = cachedSpannableText
            if (text.isEmpty()) return

            val targetY = (e.y - 24f + currentScrollOffset).coerceAtLeast(0f).toInt()
            val targetX = (e.x - 24f).coerceAtLeast(0f)
            val line = layout.getLineForVertical(targetY)
            val offset = layout.getOffsetForHorizontal(line, targetX).coerceIn(0, text.length)
            val (wStart, wEnd) = findWordBounds(text, offset)

            selectionStart = wStart
            selectionEnd = wEnd
            isSelectionMode = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            triggerRender()
            onSelectionChanged?.invoke(true, getSelectedText())
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isSelectionMode) {
                clearSelection()
                return true
            }

            // Check if user tapped on any detected link chip
            val touchX = e.x
            val touchY = e.y
            val clicked = detectedLinks.firstOrNull { it.rect.contains(touchX, touchY) }
            if (clicked != null) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onUrlClicked?.invoke(clicked.url)
                return true
            }
            return false
        }
    })

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        applyConfig(config)
    }

    fun applyConfig(newConfig: TerminalConfig) {
        config = newConfig
        currentTextSize = config.fontSize
        backgroundPaint.color = config.theme.background
        textPaint.color = config.theme.foreground
        textPaint.textSize = currentTextSize
        val tf = try {
            when (config.fontFamily.lowercase()) {
                "jetbrainsmono", "jetbrains" -> {
                    val fontFile = java.io.File(context.filesDir, "usr/share/fonts/JetBrainsMono-Regular.ttf")
                    if (fontFile.exists()) Typeface.createFromFile(fontFile)
                    else Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
                "firacode", "fira" -> {
                    val fontFile = java.io.File(context.filesDir, "usr/share/fonts/FiraCode-Regular.ttf")
                    if (fontFile.exists()) Typeface.createFromFile(fontFile)
                    else Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
                else -> Typeface.MONOSPACE
            }
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
        textPaint.typeface = tf
        ansiParser.applyTheme(config.theme, currentTextSize)
        triggerRender()
    }

    fun reloadConfig() {
        applyConfig(TerminalConfig(context))
    }

    fun updatePrompt(cwd: String, homeDir: String, username: String? = null) {
        val user = username ?: config.username.ifBlank { "void" }
        val displayPath = when {
            cwd == homeDir -> "~"
            cwd.startsWith("$homeDir/") -> "~" + cwd.removePrefix(homeDir)
            cwd == "/storage/emulated/0" -> "~/storage"
            cwd.startsWith("/storage/emulated/0/") -> "~/storage" + cwd.removePrefix("/storage/emulated/0")
            else -> cwd
        }
        currentWorkingDirectory = displayPath
        promptText = "$user:$displayPath$ "
        triggerRender()
    }

    fun setTerminalLines(lines: List<List<AnsiParser.StyledText>>) {
        synchronized(terminalLines) {
            terminalLines.clear()
            terminalLines.addAll(lines)
        }
        triggerRender()
    }

    fun getTerminalLines(): List<List<AnsiParser.StyledText>> {
        synchronized(terminalLines) {
            return terminalLines.toList()
        }
    }

    fun setInputState(buffer: String, cursor: Int) {
        currentInputBuffer = buffer
        cursorPosition = cursor.coerceIn(0, buffer.length)
        triggerRender()
    }

    fun getScrollOffset(): Float = currentScrollOffset

    fun setScrollOffset(offset: Float) {
        currentScrollOffset = offset
        triggerRender()
    }

    fun setCursorToStart() {
        cursorPosition = 0
        isUserScrolled = false
        triggerRender()
    }

    fun setCursorToEnd() {
        cursorPosition = currentInputBuffer.length
        isUserScrolled = false
        triggerRender()
    }

    fun getSelectedText(): String? {
        if (!isSelectionMode || selectionStart < 0 || selectionEnd < 0) return null
        val text = cachedSpannableText
        val minPos = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val maxPos = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        if (maxPos <= minPos) return null
        val raw = text.substring(minPos, maxPos)
        return raw.replace("\u258c", "").replace("\u2588", "").replace("\u0000", "")
    }

    fun selectAllText() {
        val text = cachedSpannableText
        if (text.isEmpty()) return
        selectionStart = 0
        selectionEnd = text.length
        isSelectionMode = true
        triggerRender()
        onSelectionChanged?.invoke(true, getSelectedText())
    }

    fun clearSelection() {
        if (isSelectionMode) {
            isSelectionMode = false
            selectionStart = -1
            selectionEnd = -1
            activeHandle = Handle.NONE
            triggerRender()
            onSelectionChanged?.invoke(false, null)
        }
    }

    private fun findWordBounds(text: CharSequence, offset: Int): Pair<Int, Int> {
        if (text.isEmpty() || offset !in 0 until text.length) return Pair(0, 0)
        var start = offset
        var end = offset
        val delims = charArrayOf(' ', '\t', '\n', '\r', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>')
        while (start > 0 && text[start - 1] !in delims) {
            start--
        }
        while (end < text.length && text[end] !in delims) {
            end++
        }
        return if (start == end && end < text.length) Pair(start, end + 1) else Pair(start, end)
    }

    fun copyAllText(): String {
        val sb = StringBuilder()
        synchronized(terminalLines) {
            for (line in terminalLines) {
                for (segment in line) {
                    sb.append(segment.text)
                }
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scaleGestureDetector.isInProgress || event.pointerCount > 1) {
            isDragging = false
            return true
        }

        val density = resources.displayMetrics.density
        val handleHitRadius = 38 * density

        // 1. Handle draggable marker handles in selection mode with priority
        if (isSelectionMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val distStart = hypot(event.x - startHandleX, event.y - (startHandleY + 8 * density))
                    val distEnd = hypot(event.x - endHandleX, event.y - (endHandleY + 8 * density))
                    if (distStart < handleHitRadius) {
                        activeHandle = Handle.START
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else if (distEnd < handleHitRadius) {
                        activeHandle = Handle.END
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeHandle != Handle.NONE) {
                        val layout = cachedLayout ?: return true
                        val text = cachedSpannableText
                        val targetY = (event.y - 24f + currentScrollOffset).coerceAtLeast(0f).toInt()
                        val targetX = (event.x - 24f).coerceAtLeast(0f)
                        val line = layout.getLineForVertical(targetY)
                        val offset = layout.getOffsetForHorizontal(line, targetX).coerceIn(0, text.length)

                        if (activeHandle == Handle.START) {
                            selectionStart = offset
                        } else if (activeHandle == Handle.END) {
                            selectionEnd = offset
                        }
                        triggerRender()
                        onSelectionChanged?.invoke(true, getSelectedText())
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeHandle != Handle.NONE) {
                        activeHandle = Handle.NONE
                        return true
                    }
                }
            }
        }

        // 2. Delegate to gestures only if not actively dragging marker handles
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Standard scrolling & soft-keyboard invocation
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - lastTouchY
                if (!isDragging && abs(deltaY) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    lastTouchY = event.y
                    synchronized(this) {
                        currentScrollOffset = (currentScrollOffset - deltaY).coerceIn(0f, maxScrollOffset)
                        isUserScrolled = currentScrollOffset < (maxScrollOffset - 20f)
                    }
                    triggerRender()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging && !isScaling && !isSelectionMode) {
                    requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
                isDragging = false
            }
        }
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) {
                    for (ch in text) {
                        if (handleModifiersForChar(ch)) {
                            continue
                        }
                        if (ch == '\n') {
                            submitCurrentBuffer()
                        } else {
                            insertChar(ch)
                        }
                    }
                    isUserScrolled = false
                    triggerRender()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
                if (beforeLength > 0 && pos > 0) {
                    val dropCount = beforeLength.coerceAtMost(pos)
                    currentInputBuffer = currentInputBuffer.substring(0, pos - dropCount) + currentInputBuffer.substring(pos)
                    cursorPosition = pos - dropCount
                    isUserScrolled = false
                    triggerRender()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                        submitCurrentBuffer()
                        return true
                    } else if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                        deleteLastChar()
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    private fun handleModifiersForChar(ch: Char): Boolean {
        if (isCtrlActive) {
            isCtrlActive = false
            when (ch.lowercaseChar()) {
                'c' -> { handleCtrlC(); return true }
                'd' -> { onCommandSubmitted?.invoke("exit"); return true }
                'l' -> { clearScreen(); return true }
                'z' -> { onCommandSubmitted?.invoke("\u001a"); return true }
                'a' -> { setCursorToStart(); return true }
                'e' -> { setCursorToEnd(); return true }
                'u' -> {
                    val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
                    currentInputBuffer = currentInputBuffer.substring(pos)
                    cursorPosition = 0
                    triggerRender()
                    return true
                }
                'k' -> {
                    val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
                    currentInputBuffer = currentInputBuffer.substring(0, pos)
                    cursorPosition = pos
                    triggerRender()
                    return true
                }
                else -> {
                    val code = ch.uppercaseChar().code - 64
                    if (code in 1..26) {
                        onCommandSubmitted?.invoke(code.toChar().toString())
                        return true
                    }
                }
            }
        }

        if (isAltActive) {
            isAltActive = false
            onCommandSubmitted?.invoke("\u001b$ch")
            return true
        }

        if (isShiftActive) {
            isShiftActive = false
            insertChar(ch.uppercaseChar())
            return true
        }

        return false
    }

    private fun insertChar(ch: Char) {
        val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
        currentInputBuffer = currentInputBuffer.substring(0, pos) + ch + currentInputBuffer.substring(pos)
        cursorPosition = pos + 1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                submitCurrentBuffer()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                deleteLastChar()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                handleHistoryUp()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleHistoryDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveCursorLeft()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveCursorRight()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                handleTabCompletion()
                return true
            }
            else -> {
                val unicodeChar = event.getUnicodeChar(event.metaState)
                if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
                    val ch = unicodeChar.toChar()
                    if (!handleModifiersForChar(ch)) {
                        insertText(ch.toString())
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun submitCurrentBuffer() {
        val command = currentInputBuffer
        currentInputBuffer = ""
        cursorPosition = 0
        if (command.isNotBlank()) {
            commandHistory.add(command)
        }
        historyIndex = -1
        isUserScrolled = false

        // Echo executed command locally into terminal history with dynamic prompt
        val echoLine = "$promptText$command"
        val styledEcho = ansiParser.parse(echoLine)
        synchronized(terminalLines) {
            terminalLines.add(styledEcho)
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }

        // Dispatch command to Broker / Session
        onCommandSubmitted?.invoke(command)

        // Poll CWD for any directory-changing command
        val trimmed = command.trim()
        if (trimmed == "cd" || trimmed.startsWith("cd ") || trimmed.startsWith("cd\t") || trimmed == "popd" || trimmed.startsWith("pushd")) {
            onCwdPollRequested?.invoke()
        }

        triggerRender()
    }

    fun insertText(text: String) {
        val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
        currentInputBuffer = currentInputBuffer.substring(0, pos) + text + currentInputBuffer.substring(pos)
        cursorPosition = pos + text.length
        isUserScrolled = false
        triggerRender()
    }

    fun deleteLastChar() {
        val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
        if (pos > 0) {
            currentInputBuffer = currentInputBuffer.substring(0, pos - 1) + currentInputBuffer.substring(pos)
            cursorPosition = pos - 1
            isUserScrolled = false
            triggerRender()
        }
    }

    fun deleteCharUnderCursor() {
        val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
        if (pos < currentInputBuffer.length) {
            currentInputBuffer = currentInputBuffer.substring(0, pos) + currentInputBuffer.substring(pos + 1)
            isUserScrolled = false
            triggerRender()
        }
    }

    fun moveCursorLeft() {
        if (cursorPosition > 0) {
            cursorPosition--
            isUserScrolled = false
            triggerRender()
        }
    }

    fun moveCursorRight() {
        if (cursorPosition < currentInputBuffer.length) {
            cursorPosition++
            isUserScrolled = false
            triggerRender()
        }
    }

    fun clearScreen() {
        synchronized(terminalLines) {
            terminalLines.clear()
        }
        currentScrollOffset = 0f
        isUserScrolled = false
        triggerRender()
    }

    fun handleCtrlC() {
        val line = "$promptText$currentInputBuffer^C"
        currentInputBuffer = ""
        cursorPosition = 0
        historyIndex = -1
        isUserScrolled = false
        val styled = ansiParser.parse(line)
        synchronized(terminalLines) {
            terminalLines.add(styled)
        }
        onCommandSubmitted?.invoke("\u0003")
        triggerRender()
    }

    fun handleHistoryUp() {
        if (commandHistory.isNotEmpty()) {
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                currentInputBuffer = commandHistory[commandHistory.size - 1 - historyIndex]
                cursorPosition = currentInputBuffer.length
                isUserScrolled = false
                triggerRender()
            }
        }
    }

    fun handleHistoryDown() {
        if (historyIndex > 0) {
            historyIndex--
            currentInputBuffer = commandHistory[commandHistory.size - 1 - historyIndex]
            cursorPosition = currentInputBuffer.length
            isUserScrolled = false
            triggerRender()
        } else if (historyIndex == 0) {
            historyIndex = -1
            currentInputBuffer = ""
            cursorPosition = 0
            isUserScrolled = false
            triggerRender()
        }
    }

    fun handleTabCompletion() {
        if (currentInputBuffer.isEmpty()) {
            insertText("    ")
            return
        }
        val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
        val textBefore = currentInputBuffer.substring(0, pos)
        val lastDelim = textBefore.lastIndexOfAny(charArrayOf(' ', '/', '|', '&', ';'))
        val token = if (lastDelim >= 0) textBefore.substring(lastDelim + 1) else textBefore
        if (token.isEmpty()) {
            insertText(" ")
            return
        }

        val binDir = java.io.File(context.filesDir, "usr/bin")
        val homeDir = java.io.File(context.filesDir, "home")
        val candidates = mutableSetOf<String>()
        binDir.list()?.let { candidates.addAll(it) }
        homeDir.list()?.let { candidates.addAll(it) }

        val matches = candidates.filter { it.startsWith(token) }.sorted()
        if (matches.size == 1) {
            val suffix = matches.first().substring(token.length) + " "
            insertText(suffix)
        } else if (matches.size > 1) {
            var prefix = matches.first()
            for (m in matches) {
                prefix = prefix.commonPrefixWith(m)
            }
            if (prefix.length > token.length) {
                insertText(prefix.substring(token.length))
            } else {
                appendOutput("\r\n\u001b[36m" + matches.joinToString("  ") + "\u001b[0m\r\n")
            }
        }
    }

    /**
     * Safely appends and parses new terminal output.
     */
    fun appendOutput(rawOutput: String) {
        if (rawOutput.contains("\u001b[2J") || rawOutput.contains("\u001b[H\u001b[2J") || rawOutput.contains("\u001b[2J\u001b[H")) {
            synchronized(terminalLines) {
                terminalLines.clear()
            }
        }
        val styledLines = ansiParser.parseLines(rawOutput)
        synchronized(terminalLines) {
            terminalLines.addAll(styledLines)
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }
        triggerRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        reloadConfig()
        isRunning = true
        drawingThread = Thread(this, "VoidTerm-Renderer").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val charWidth = textPaint.measureText("W").coerceAtLeast(10f)
        val lineHeight = (textPaint.fontSpacing).coerceAtLeast(15f)
        val cols = (width / charWidth).toInt().coerceAtLeast(20)
        val rows = (height / lineHeight).toInt().coerceAtLeast(10)
        onDimensionsChanged?.invoke(rows, cols)
        triggerRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        try {
            drawingThread?.join(500)
        } catch (_: InterruptedException) {
            Log.w("VoidTerm", "Drawing thread join interrupted")
        }
    }

    override fun run() {
        while (isRunning) {
            if (!holder.surface.isValid) {
                try {
                    Thread.sleep(16)
                } catch (_: Exception) {}
                continue
            }

            renderFrame()

            try {
                Thread.sleep(16) // ~60fps rendering cadence
            } catch (_: Exception) {}
        }
    }

    fun triggerRender() {
        if (holder.surface.isValid) {
            renderFrame()
        }
    }

    private fun renderFrame() {
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    holder.lockCanvas()
                }
            } else {
                holder.lockCanvas()
            }

            if (canvas != null) {
                drawTerminal(canvas)
            }
        } catch (e: Exception) {
            Log.e("VoidTerm", "Error drawing frame: ${e.message}")
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.e("VoidTerm", "Error posting canvas: ${e.message}")
                }
            }
        }
    }

    private fun drawTerminal(canvas: Canvas) {
        // 1. Draw solid background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val density = resources.displayMetrics.density
        val paddingX = (config.paddingHorizontalDp * density).coerceAtLeast(4f * density)
        val paddingY = (config.paddingTopDp * density).coerceAtLeast(4f * density)
        val layoutWidth = (width - (paddingX * 2).toInt()).coerceAtLeast(100)

        // 2. Build styled Spannable buffer with ANSI formatting + Markdown highlighting
        val spannableBuilder = SpannableStringBuilder()
        synchronized(terminalLines) {
            for ((lineIndex, lineBlocks) in terminalLines.withIndex()) {
                for (block in lineBlocks) {
                    val start = spannableBuilder.length
                    spannableBuilder.append(block.text)
                    val end = spannableBuilder.length

                    if (end > start) {
                        spannableBuilder.setSpan(
                            ForegroundColorSpan(block.paint.color),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        if (block.paint.isFakeBoldText) {
                            spannableBuilder.setSpan(
                                StyleSpan(Typeface.BOLD),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }

                        if (block.backgroundColor != null) {
                            spannableBuilder.setSpan(
                                BackgroundColorSpan(block.backgroundColor),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }

                        if (block.isItalic) {
                            spannableBuilder.setSpan(
                                StyleSpan(Typeface.ITALIC),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                if (lineIndex < terminalLines.size - 1) {
                    spannableBuilder.append("\n")
                }
            }
        }

        // 3. Append active dynamic prompt, user input buffer, and blinking cursor
        if (spannableBuilder.isNotEmpty()) {
            spannableBuilder.append("\n")
        }

        val promptStart = spannableBuilder.length
        spannableBuilder.append(promptText)
        val promptEnd = spannableBuilder.length
        spannableBuilder.setSpan(
            ForegroundColorSpan(config.theme.promptColor),
            promptStart,
            promptEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableBuilder.setSpan(
            StyleSpan(Typeface.BOLD),
            promptStart,
            promptEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Append current typed buffer with cursor placed at cursorPosition
        val safeCursor = cursorPosition.coerceIn(0, currentInputBuffer.length)
        val beforeCursor = currentInputBuffer.substring(0, safeCursor)
        val afterCursor = currentInputBuffer.substring(safeCursor)

        val beforeStart = spannableBuilder.length
        spannableBuilder.append(beforeCursor)
        val beforeEnd = spannableBuilder.length
        if (beforeEnd > beforeStart) {
            spannableBuilder.setSpan(
                ForegroundColorSpan(config.theme.foreground),
                beforeStart,
                beforeEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Blinking cursor
        val blinkRate = config.cursorBlinkRateMs.coerceIn(100L, 3000L)
        val isCursorVisible = if (config.cursorBlink) {
            (System.currentTimeMillis() / blinkRate) % 2 == 0L
        } else {
            true
        }
        val cursorSymbol = if (isCursorVisible) config.cursorStyle.symbol else " "
        val cursorStart = spannableBuilder.length
        spannableBuilder.append(cursorSymbol)
        val cursorEnd = spannableBuilder.length
        spannableBuilder.setSpan(
            ForegroundColorSpan(config.theme.cursor),
            cursorStart,
            cursorEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val afterStart = spannableBuilder.length
        spannableBuilder.append(afterCursor)
        val afterEnd = spannableBuilder.length
        if (afterEnd > afterStart) {
            spannableBuilder.setSpan(
                ForegroundColorSpan(config.theme.foreground),
                afterStart,
                afterEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 4. Apply selection highlight span if in selection mode
        if (isSelectionMode && selectionStart >= 0 && selectionEnd >= 0) {
            val minPos = minOf(selectionStart, selectionEnd).coerceIn(0, spannableBuilder.length)
            val maxPos = maxOf(selectionStart, selectionEnd).coerceIn(0, spannableBuilder.length)
            if (maxPos > minPos) {
                spannableBuilder.setSpan(
                    BackgroundColorSpan(Color.parseColor("#80BD93F9")),
                    minPos,
                    maxPos,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // 5. Construct StaticLayout with character wrapping & no hyphens
        textPaint.textSize = currentTextSize
        val staticLayout: StaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder.obtain(spannableBuilder, 0, spannableBuilder.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            }
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                spannableBuilder,
                textPaint,
                layoutWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.15f,
                0f,
                false
            )
        }

        cachedLayout = staticLayout
        cachedSpannableText = spannableBuilder.toString()

        // 6. Calculate vertical scroll offset (supports user drag scroll + auto-scroll)
        val layoutHeight = staticLayout.height.toFloat()
        val visibleHeight = height.toFloat() - (paddingY * 2)
        val maxScroll = (layoutHeight - visibleHeight).coerceAtLeast(0f)
        maxScrollOffset = maxScroll

        if (!isUserScrolled && !isSelectionMode) {
            currentScrollOffset = maxScroll
        } else {
            currentScrollOffset = currentScrollOffset.coerceIn(0f, maxScroll)
        }

        // 7. Draw the layout to the canvas
        canvas.save()
        canvas.translate(paddingX, paddingY - currentScrollOffset)
        staticLayout.draw(canvas)
        canvas.restore()

        // 8. Draw selection marker pin handles on top of text
        if (isSelectionMode && selectionStart >= 0 && selectionEnd >= 0) {
            val minPos = minOf(selectionStart, selectionEnd).coerceIn(0, spannableBuilder.length)
            val maxPos = maxOf(selectionStart, selectionEnd).coerceIn(0, spannableBuilder.length)
            if (maxPos > minPos) {
                val density = resources.displayMetrics.density
                val startLine = staticLayout.getLineForOffset(minPos)
                val startX = paddingX + staticLayout.getPrimaryHorizontal(minPos)
                val startY = paddingY - currentScrollOffset + staticLayout.getLineBottom(startLine)

                val endLine = staticLayout.getLineForOffset(maxPos)
                val endX = paddingX + staticLayout.getPrimaryHorizontal(maxPos)
                val endY = paddingY - currentScrollOffset + staticLayout.getLineBottom(endLine)

                startHandleX = startX
                startHandleY = startY
                endHandleX = endX
                endHandleY = endY

                // Draw start pin marker (circle with dark stroke)
                canvas.drawCircle(startX, startY + (8 * density), 10 * density, handlePaint)
                canvas.drawCircle(startX, startY + (8 * density), 10 * density, handleStrokePaint)

                // Draw end pin marker (circle with dark stroke)
                canvas.drawCircle(endX, endY + (8 * density), 10 * density, handlePaint)
                canvas.drawCircle(endX, endY + (8 * density), 10 * density, handleStrokePaint)
            }
        }

        // 9. Recompute link clickable bounding boxes
        if (config.linkDetection) {
            val newDetectedLinks = mutableListOf<LinkDetector.DetectedLink>()
            val fullText = cachedSpannableText
            val matches = LinkDetector.URL_REGEX.findAll(fullText)
            for (match in matches) {
                val s = match.range.first
                val e = match.range.last + 1
                if (s in 0 until fullText.length && e <= fullText.length) {
                    val sLine = staticLayout.getLineForOffset(s)
                    val sX = paddingX + staticLayout.getPrimaryHorizontal(s)
                    val eX = paddingX + staticLayout.getPrimaryHorizontal(e).coerceAtLeast(sX + 40f)
                    val topY = paddingY - currentScrollOffset + staticLayout.getLineTop(sLine)
                    val botY = paddingY - currentScrollOffset + staticLayout.getLineBottom(sLine)
                    if (botY >= 0 && topY <= height) {
                        newDetectedLinks.add(
                            LinkDetector.DetectedLink(
                                url = match.value,
                                rect = android.graphics.RectF(sX, topY, eX, botY)
                            )
                        )
                    }
                }
            }
            synchronized(detectedLinks) {
                detectedLinks.clear()
                detectedLinks.addAll(newDetectedLinks)
            }
        }
    }
}
