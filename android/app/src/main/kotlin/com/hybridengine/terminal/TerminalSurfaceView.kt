package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.TextPaint
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
import kotlin.math.max
import kotlin.math.min

/**
 * TerminalSurfaceView: High-performance 2D character-cell Virtual Terminal display.
 * Renders DEC VT100 / XTerm grids with full truecolor and alternate screen buffer support,
 * streaming raw keyboard events directly to the POSIX PTY without synthetic line-buffering.
 */
class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    private var drawingThread: Thread? = null
    @Volatile
    private var isRunning = false

    // Active terminal emulator and buffer
    var emulator: TerminalEmulator = TerminalEmulator(TerminalBuffer(24, 80))
    var activeSession: TabSession? = null

    // Callbacks
    var onInputBytes: ((ByteArray) -> Unit)? = null
    var onCommandSubmitted: ((String) -> Unit)? = null
    var onDimensionsChanged: ((rows: Int, cols: Int) -> Unit)? = null
    var onUrlClicked: ((String) -> Unit)? = null
    var onCwdPollRequested: (() -> Unit)? = null
    var onSelectionChanged: ((Boolean, String?) -> Unit)? = null
    var onModifierStateChanged: (() -> Unit)? = null

    // Active terminal configuration
    var config: TerminalConfig = TerminalConfig(context)
        private set

    // Working Directory & Prompt State
    var currentWorkingDirectory: String = "~"
        private set
    var promptText: String = "void:~$ "
        private set

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

    // Input buffer compatibility fields
    var currentInputBuffer: String = ""
    var cursorPosition: Int = 0

    // Text Selection Mode & Marker Handles
    var isSelectionMode: Boolean = false
        private set
    var selectionStartCol: Int = -1
    var selectionStartRow: Int = -1
    var selectionEndCol: Int = -1
    var selectionEndRow: Int = -1

    val detectedLinks = mutableListOf<LinkDetector.DetectedLink>()

    // Scrollback tracking
    private var scrollbackOffsetLines: Int = 0
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Monospace Character Cell Metrics
    private var charWidth: Float = 24f
    private var charHeight: Float = 48f
    private var charDescent: Float = 10f

    // Rendering Paints
    private val backgroundPaint = Paint().apply {
        color = config.theme.background
    }
    private val cellFillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#60BD93F9")
        style = Paint.Style.FILL
    }

    private var currentTextSize = config.fontSize
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = currentTextSize
        color = config.theme.foreground
    }

    private val ansiParser = AnsiParser()
    private val terminalLines = mutableListOf<List<AnsiParser.StyledText>>()

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
            updateCharMetrics()
            notifyDimensions()
            triggerRender()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val density = resources.displayMetrics.density
            val paddingX = (config.paddingHorizontalDp * density).coerceAtLeast(4f * density)
            val paddingY = (config.paddingTopDp * density).coerceAtLeast(4f * density)

            val touchCol = ((e.x - paddingX) / charWidth).toInt().coerceIn(0, emulator.buffer.activeCols - 1)
            val touchRow = ((e.y - paddingY) / charHeight).toInt().coerceIn(0, emulator.buffer.activeRows - 1)

            selectionStartCol = max(0, touchCol - 5)
            selectionStartRow = touchRow
            selectionEndCol = min(emulator.buffer.activeCols - 1, touchCol + 5)
            selectionEndRow = touchRow
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

            // Bring up soft keyboard
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this@TerminalSurfaceView, InputMethodManager.SHOW_IMPLICIT)
            return true
        }
    })

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        applyConfig(config)
    }

    fun attachSession(session: TabSession) {
        activeSession = session
        emulator = session.emulator
        emulator.onScreenInvalidated = {
            triggerRender()
        }
        notifyDimensions()
        triggerRender()
    }

    private fun updateCharMetrics() {
        charWidth = textPaint.measureText("W").coerceAtLeast(10f)
        charHeight = textPaint.fontSpacing.coerceAtLeast(15f)
        charDescent = textPaint.fontMetrics.descent
    }

    fun calculateCols(): Int {
        val density = resources.displayMetrics.density
        val paddingX = (config.paddingHorizontalDp * density).coerceAtLeast(4f * density)
        val availableW = width - (paddingX * 2)
        return max(10, (availableW / charWidth).toInt())
    }

    fun calculateRows(): Int {
        val density = resources.displayMetrics.density
        val paddingY = (config.paddingTopDp * density).coerceAtLeast(4f * density)
        val availableH = height - (paddingY * 2)
        return max(5, (availableH / charHeight).toInt())
    }

    private fun notifyDimensions() {
        if (width > 0 && height > 0) {
            val cols = calculateCols()
            val rows = calculateRows()
            emulator.resize(rows, cols)
            onDimensionsChanged?.invoke(rows, cols)
        }
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
        updateCharMetrics()
        notifyDimensions()
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
    }

    fun setTerminalLines(lines: List<List<AnsiParser.StyledText>>) {
        // Compatibility stub for existing multi-tab state transitions
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
    }

    fun getScrollOffset(): Float = scrollbackOffsetLines.toFloat()

    fun setScrollOffset(offset: Float) {
        scrollbackOffsetLines = offset.toInt().coerceIn(0, emulator.buffer.scrollbackSize)
        triggerRender()
    }

    fun appendOutput(rawOutput: String) {
        emulator.feed(rawOutput)
        triggerRender()
    }

    // --- Direct Input Streaming to PTY ---

    fun sendInput(bytes: ByteArray) {
        val cb = onInputBytes
        if (cb != null) {
            cb(bytes)
        } else {
            activeSession?.processSession?.write(bytes)
        }
        scrollbackOffsetLines = 0
    }

    fun sendInput(text: String) {
        sendInput(text.toByteArray(Charsets.UTF_8))
    }

    fun insertText(text: String) {
        sendInput(text)
    }

    fun deleteLastChar() {
        sendInput(byteArrayOf(0x7F))
    }

    fun deleteCharUnderCursor() {
        sendInput("\u001b[3~")
    }

    fun moveCursorLeft() {
        sendInput(if (emulator.isCursorKeysApplication) "\u001bOD" else "\u001b[D")
    }

    fun moveCursorRight() {
        sendInput(if (emulator.isCursorKeysApplication) "\u001bOC" else "\u001b[C")
    }

    fun handleHistoryUp() {
        sendInput(if (emulator.isCursorKeysApplication) "\u001bOA" else "\u001b[A")
    }

    fun handleHistoryDown() {
        sendInput(if (emulator.isCursorKeysApplication) "\u001bOB" else "\u001b[B")
    }

    fun handleTabCompletion() {
        sendInput("\t")
    }

    fun handleCtrlC() {
        sendInput(byteArrayOf(0x03))
    }

    fun setCursorToStart() {
        sendInput(if (emulator.buffer.isAlternateScreen) "\u001b[H" else "\u0001")
    }

    fun setCursorToEnd() {
        sendInput(if (emulator.buffer.isAlternateScreen) "\u001b[F" else "\u0005")
    }

    fun clearScreen() {
        sendInput("\u000C") // Ctrl+L
        emulator.buffer.clearScreen(2)
        triggerRender()
    }

    // --- Touch & Gesture Event Handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

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
                    val linesScrolled = (deltaY / charHeight).toInt()
                    if (linesScrolled != 0) {
                        scrollbackOffsetLines = (scrollbackOffsetLines + linesScrolled)
                            .coerceIn(0, emulator.buffer.scrollbackSize)
                        triggerRender()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    // --- InputConnection & Soft Keyboard Direct Stream ---

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    for (ch in text) {
                        if (isCtrlActive) {
                            isCtrlActive = false
                            val b = TerminalKeyEncoder.encodeCtrlChar(ch)
                            if (b != null) {
                                sendInput(byteArrayOf(b))
                                continue
                            }
                        }
                        if (isAltActive) {
                            isAltActive = false
                            sendInput(byteArrayOf(0x1B, ch.code.toByte()))
                            continue
                        }
                        if (isShiftActive) {
                            isShiftActive = false
                            sendInput(ch.uppercaseChar().toString())
                            continue
                        }
                        if (ch == '\n') {
                            sendInput("\r")
                        } else {
                            sendInput(ch.toString())
                        }
                    }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    for (i in 0 until beforeLength) {
                        deleteLastChar()
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val encoded = TerminalKeyEncoder.encodeKeyEvent(
                        event.keyCode,
                        event,
                        isCtrl = isCtrlActive,
                        isAlt = isAltActive,
                        isShift = isShiftActive,
                        cursorKeysApp = emulator.isCursorKeysApplication
                    )
                    if (isCtrlActive) isCtrlActive = false
                    if (isAltActive) isAltActive = false
                    if (isShiftActive) isShiftActive = false

                    if (encoded != null) {
                        sendInput(encoded)
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val encoded = TerminalKeyEncoder.encodeKeyEvent(
            keyCode,
            event,
            isCtrl = isCtrlActive,
            isAlt = isAltActive,
            isShift = isShiftActive,
            cursorKeysApp = emulator.isCursorKeysApplication
        )
        if (isCtrlActive) isCtrlActive = false
        if (isAltActive) isAltActive = false
        if (isShiftActive) isShiftActive = false

        if (encoded != null) {
            sendInput(encoded)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Selection and Clipboard APIs ---

    fun getSelectedText(): String? {
        if (!isSelectionMode || selectionStartRow < 0 || selectionEndRow < 0) return null
        val buffer = emulator.buffer
        val sb = StringBuilder()
        val minR = min(selectionStartRow, selectionEndRow).coerceIn(0, buffer.activeRows - 1)
        val maxR = max(selectionStartRow, selectionEndRow).coerceIn(0, buffer.activeRows - 1)
        val minC = min(selectionStartCol, selectionEndCol).coerceIn(0, buffer.activeCols - 1)
        val maxC = max(selectionStartCol, selectionEndCol).coerceIn(0, buffer.activeCols - 1)

        for (r in minR..maxR) {
            val line = buffer.getLine(r, scrollbackOffsetLines)
            for (c in minC..maxC) {
                sb.append(line[c].char)
            }
            if (r < maxR) sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun selectAllText() {
        val buffer = emulator.buffer
        selectionStartRow = 0
        selectionStartCol = 0
        selectionEndRow = buffer.activeRows - 1
        selectionEndCol = buffer.activeCols - 1
        isSelectionMode = true
        triggerRender()
        onSelectionChanged?.invoke(true, getSelectedText())
    }

    fun clearSelection() {
        if (isSelectionMode) {
            isSelectionMode = false
            selectionStartRow = -1
            selectionStartCol = -1
            selectionEndRow = -1
            selectionEndCol = -1
            triggerRender()
            onSelectionChanged?.invoke(false, null)
        }
    }

    fun copyAllText(): String {
        return emulator.buffer.getAllText().trimEnd()
    }

    // --- Surface Lifecycle & 60 FPS Render Loop ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        reloadConfig()
        isRunning = true
        drawingThread = Thread(this, "VoidTerm-Renderer").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateCharMetrics()
        notifyDimensions()
        triggerRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        drawingThread?.interrupt()
        try {
            drawingThread?.join(500)
        } catch (_: Exception) {}
        drawingThread = null
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
                Thread.sleep(16) // ~60fps cadence
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

    private val textRunBuilder = StringBuilder()
    private val bgRect = RectF()

    private fun drawTerminal(canvas: Canvas) {
        // 1. Draw solid background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val density = resources.displayMetrics.density
        val paddingX = (config.paddingHorizontalDp * density).coerceAtLeast(4f * density)
        val paddingY = (config.paddingTopDp * density).coerceAtLeast(4f * density)

        val buffer = emulator.buffer
        val termRows = buffer.activeRows
        val termCols = buffer.activeCols

        // 2. Render 2D Character Cell Matrix row-by-row
        for (r in 0 until termRows) {
            val line = buffer.getLine(r, scrollbackOffsetLines)
            val lineY = paddingY + (r * charHeight)
            val baselineY = lineY + charHeight - charDescent

            var c = 0
            while (c < termCols) {
                val cell = line[c]
                val startCol = c
                val fg = cell.fg
                val bg = cell.bg
                val flags = cell.flags

                textRunBuilder.setLength(0)
                while (c < termCols && line[c].fg == fg && line[c].bg == bg && line[c].flags == flags) {
                    textRunBuilder.append(line[c].char)
                    c++
                }

                val runLen = c - startCol
                val runStartX = paddingX + (startCol * charWidth)
                val runWidth = runLen * charWidth

                // Determine effective colors with inverse flag support
                val isInverse = (flags and TerminalChar.FLAG_INVERSE) != 0
                val effectiveBg = if (isInverse) fg else bg
                val effectiveFg = if (isInverse) {
                    if (bg == 0 || bg == Color.TRANSPARENT) config.theme.background else bg
                } else {
                    fg
                }

                // Draw cell background rect if non-transparent
                if (effectiveBg != 0 && effectiveBg != Color.TRANSPARENT) {
                    cellFillPaint.color = effectiveBg
                    bgRect.set(runStartX, lineY, runStartX + runWidth, lineY + charHeight)
                    canvas.drawRect(bgRect, cellFillPaint)
                }

                // Draw text run with attributes
                textPaint.color = effectiveFg
                textPaint.isFakeBoldText = (flags and TerminalChar.FLAG_BOLD) != 0
                textPaint.isUnderlineText = (flags and TerminalChar.FLAG_UNDERLINE) != 0
                canvas.drawText(textRunBuilder.toString(), runStartX, baselineY, textPaint)
            }
        }

        // 3. Selection Highlighting
        if (isSelectionMode && selectionStartRow >= 0 && selectionEndRow >= 0) {
            val minR = min(selectionStartRow, selectionEndRow).coerceIn(0, termRows - 1)
            val maxR = max(selectionStartRow, selectionEndRow).coerceIn(0, termRows - 1)
            val minC = min(selectionStartCol, selectionEndCol).coerceIn(0, termCols - 1)
            val maxC = max(selectionStartCol, selectionEndCol).coerceIn(0, termCols - 1)

            for (r in minR..maxR) {
                val startX = paddingX + (minC * charWidth)
                val endX = paddingX + ((maxC + 1) * charWidth)
                val topY = paddingY + (r * charHeight)
                val bottomY = topY + charHeight
                canvas.drawRect(startX, topY, endX, bottomY, selectionPaint)
            }
        }

        // 4. Cursor Rendering (Active only when at bottom scroll)
        if (scrollbackOffsetLines == 0 && buffer.isCursorVisible) {
            val blinkRate = config.cursorBlinkRateMs.coerceIn(100L, 3000L)
            val isCursorBlinkOn = if (config.cursorBlink) {
                (System.currentTimeMillis() / blinkRate) % 2 == 0L
            } else {
                true
            }

            if (isCursorBlinkOn) {
                val curCol = buffer.cursorCol.coerceIn(0, termCols - 1)
                val curRow = buffer.cursorRow.coerceIn(0, termRows - 1)
                val curX = paddingX + (curCol * charWidth)
                val curY = paddingY + (curRow * charHeight)

                cursorPaint.color = config.theme.cursor
                when (config.cursorStyle) {
                    CursorStyle.UNDERLINE -> {
                        canvas.drawRect(curX, curY + charHeight - 4f * density, curX + charWidth, curY + charHeight, cursorPaint)
                    }
                    CursorStyle.BAR -> {
                        canvas.drawRect(curX, curY, curX + 2.5f * density, curY + charHeight, cursorPaint)
                    }
                    else -> {
                        canvas.drawRect(curX, curY, curX + charWidth, curY + charHeight, cursorPaint)
                        // Invert text inside block cursor
                        val line = buffer.getLine(curRow, 0)
                        val ch = line[curCol].char
                        if (ch != ' ') {
                            textPaint.color = config.theme.background
                            val baselineY = curY + charHeight - charDescent
                            canvas.drawText(ch.toString(), curX, baselineY, textPaint)
                        }
                    }
                }
            }
        }
    }
}
