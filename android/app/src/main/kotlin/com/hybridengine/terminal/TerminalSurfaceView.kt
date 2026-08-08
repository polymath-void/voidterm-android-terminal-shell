package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

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

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A") // Deep true black
    }

    private val lineHeight = 52f

    init {
        holder.addCallback(this)
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

        synchronized(terminalLines) {
            val totalLines = terminalLines.size
            if (totalLines == 0) return

            val maxVisibleLines = ((height.toFloat() - 40f) / lineHeight).toInt().coerceAtLeast(1)
            val visibleLines = if (totalLines > maxVisibleLines) {
                terminalLines.subList(totalLines - maxVisibleLines, totalLines)
            } else {
                terminalLines
            }

            var yOffset = 50f
            val startX = 20f

            // Render each visible line sequentially
            for (lineBlocks in visibleLines) {
                var xOffset = startX

                // Render each styled text segment across the X axis
                for (block in lineBlocks) {
                    if (block.text.isNotEmpty()) {
                        canvas.drawText(block.text, xOffset, yOffset, block.paint)
                        xOffset += block.paint.measureText(block.text)
                    }
                }
                yOffset += lineHeight
            }
        }
    }
}
