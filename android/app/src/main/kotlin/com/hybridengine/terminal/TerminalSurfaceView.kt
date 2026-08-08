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
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

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
    var onCommandSubmitted: ((String) -> Unit)? = null
    private val promptText = "root@voidterm:~# "

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_UP && !isScaling) {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
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
                            currentInputBuffer += ch
                        }
                    }
                    triggerRender()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && currentInputBuffer.isNotEmpty()) {
                    val dropCount = beforeLength.coerceAtMost(currentInputBuffer.length)
                    currentInputBuffer = currentInputBuffer.dropLast(dropCount)
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
                        if (currentInputBuffer.isNotEmpty()) {
                            currentInputBuffer = currentInputBuffer.dropLast(1)
                            triggerRender()
                        }
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
                if (currentInputBuffer.isNotEmpty()) {
                    currentInputBuffer = currentInputBuffer.dropLast(1)
                    triggerRender()
                }
                return true
            }
            else -> {
                val unicodeChar = event.getUnicodeChar(event.metaState)
                if (unicodeChar != 0 && !Character.isISOControl(unicodeChar)) {
                    currentInputBuffer += unicodeChar.toChar()
                    triggerRender()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun submitCurrentBuffer() {
        val command = currentInputBuffer
        currentInputBuffer = ""

        // Echo the executed command locally into terminal history
        val echoLine = "$promptText$command"
        val styledEcho = ansiParser.parse(echoLine)
        synchronized(terminalLines) {
            terminalLines.add(styledEcho)
            if (terminalLines.size > 1000) {
                terminalLines.subList(0, terminalLines.size - 1000).clear()
            }
        }

        // Dispatch command to Rust Broker -> Debian microVM vsock
        onCommandSubmitted?.invoke(command)
        triggerRender()
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

        // Append current typed buffer
        val inputStart = spannableBuilder.length
        spannableBuilder.append(currentInputBuffer)
        val inputEnd = spannableBuilder.length
        if (inputEnd > inputStart) {
            spannableBuilder.setSpan(
                ForegroundColorSpan(config.theme.foreground),
                inputStart,
                inputEnd,
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

        // 5. Calculate vertical scroll offset to keep latest active prompt in view
        val layoutHeight = staticLayout.height.toFloat()
        val visibleHeight = height.toFloat() - (paddingY * 2)
        val scrollOffsetY = if (layoutHeight > visibleHeight) layoutHeight - visibleHeight else 0f

        // 6. Draw the word-wrapped layout to the canvas
        canvas.save()
        canvas.translate(paddingX, paddingY - scrollOffsetY)
        staticLayout.draw(canvas)
        canvas.restore()
    }
}
