package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
    private val textBuffer = mutableListOf<String>()
    
    // Minimalist, hardware-native typography configuration
    private val textPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0") // High-contrast off-white
        textSize = 42f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A") // Deep true black
    }

    init {
        holder.addCallback(this)
    }

    // Safely append new text from the Broker
    fun appendOutput(text: String) {
        synchronized(textBuffer) {
            val lines = text.split("\n")
            textBuffer.addAll(lines)
            
            // Keep buffer bounded
            if (textBuffer.size > 500) {
                textBuffer.subList(0, textBuffer.size - 500).clear()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        drawingThread = Thread(this, "VoidTerm-Renderer").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

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
            
            try {
                Thread.sleep(16) // ~60fps
            } catch (_: Exception) {}
        }
    }

    private fun drawTerminal(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        synchronized(textBuffer) {
            var yOffset = height.toFloat() - 50f
            
            for (i in textBuffer.indices.reversed()) {
                if (yOffset < 0) break
                canvas.drawText(textBuffer[i], 20f, yOffset, textPaint)
                yOffset -= (textPaint.textSize + 10f)
            }
        }
    }
}
