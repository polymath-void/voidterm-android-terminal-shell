package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.Log
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

    // Active inline input state
    var currentInputBuffer: String = ""
    var cursorPosition: Int = 0
    var onCommandSubmitted: ((String) -> Unit)? = null
    private val promptText = "voidterm:$ "

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
        ansiParser.applyTheme(config.theme, currentTextSize)
        triggerRender()
    }

    fun reloadConfig() {
        applyConfig(TerminalConfig(context))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        if (scaleGestureDetector.isInProgress || event.pointerCount > 1) {
            isDragging = false
            return true
        }

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
                        // If user scrolled within 20px of bottom, resume auto-scrolling
                        isUserScrolled = currentScrollOffset < (maxScrollOffset - 20f)
                    }
                    triggerRender()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging && !isScaling) {
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
                        if (ch == '\n') {
                            submitCurrentBuffer()
                        } else {
                            val pos = cursorPosition.coerceIn(0, currentInputBuffer.length)
                            currentInputBuffer = currentInputBuffer.substring(0, pos) + ch + currentInputBuffer.substring(pos)
                            cursorPosition = pos + 1
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
                    insertText(unicodeChar.toChar().toString())
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun submitCurrentBuffer() {
        val command = currentInputBuffer
        currentInputBuffer = ""
        cursorPosition = 0
        if (command.isNotBlank()) {
            commandHistory.add(command)
        }
        historyIndex = -1
        isUserScrolled = false

        // Echo the executed command locally into terminal history
        val echoLine = "$promptText$command"
        val styledEcho = ansiParser.parse(echoLine)
        synchronized(terminalLines) {
            terminalLines.add(styledEcho)
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }

        // Dispatch command to Broker
        onCommandSubmitted?.invoke(command)
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
     * Safely appends and parses new terminal output from the Broker.
     */
    fun appendOutput(rawOutput: String) {
        val styledLines = ansiParser.parseLines(rawOutput)
        synchronized(terminalLines) {
            terminalLines.addAll(styledLines)

            // Bound memory buffer to 1000 lines
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }
        triggerRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        drawingThread = Thread(this, "VoidTerm-Renderer").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        triggerRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        try {
            drawingThread?.join(500)
        } catch (e: InterruptedException) {
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

    private fun triggerRender() {
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

        val paddingX = 24f
        val paddingY = 24f
        val layoutWidth = (width - (paddingX * 2).toInt()).coerceAtLeast(100)

        // 2. Build styled Spannable buffer with ANSI formatting + Active Inline Input Prompt
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
                    }
                }
                if (lineIndex < terminalLines.size - 1) {
                    spannableBuilder.append("\n")
                }
            }
        }

        // 3. Append active prompt, user input buffer, and blinking cursor
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

        // Configurable cursor shape & 500ms blinking timer
        val isCursorVisible = if (config.cursorBlink) {
            (System.currentTimeMillis() / 500) % 2 == 0L
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

        // 4. Construct StaticLayout for native word wrapping and multi-line rendering
        textPaint.textSize = currentTextSize
        val staticLayout: StaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(spannableBuilder, 0, spannableBuilder.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
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

        // 5. Calculate vertical scroll offset (supports user drag scroll + auto-scroll)
        val layoutHeight = staticLayout.height.toFloat()
        val visibleHeight = height.toFloat() - (paddingY * 2)
        val maxScroll = (layoutHeight - visibleHeight).coerceAtLeast(0f)
        maxScrollOffset = maxScroll

        if (!isUserScrolled) {
            currentScrollOffset = maxScroll
        } else {
            currentScrollOffset = currentScrollOffset.coerceIn(0f, maxScroll)
        }

        // 6. Draw the word-wrapped layout to the canvas
        canvas.save()
        canvas.translate(paddingX, paddingY - currentScrollOffset)
        staticLayout.draw(canvas)
        canvas.restore()
    }
}
