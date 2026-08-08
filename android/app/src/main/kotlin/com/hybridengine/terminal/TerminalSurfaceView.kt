package com.hybridengine.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    private var drawingThread: Thread? = null
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

    // Safely append new text from the Rust Broker
    fun appendOutput(text: String) {
        synchronized(textBuffer) {
            // Split incoming streams into lines for the buffer
            val lines = text.split("\n")
            textBuffer.addAll(lines)
            
            // Keep buffer from infinitely expanding (e.g., retain last 500 lines)
            if (textBuffer.size > 500) {
                textBuffer.subList(0, textBuffer.size - 500).clear()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        drawingThread = Thread(this).apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        isRunning = false
        while (retry) {
            try {
                drawingThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    override fun run() {
        while (isRunning) {
            if (!holder.surface.isValid) continue

            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas() // Hardware accelerated drawing
                if (canvas != null) {
                    drawTerminal(canvas)
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            
            // Limit to ~60fps to save battery while remaining visually instantaneous
            Thread.sleep(16) 
        }
    }

    private fun drawTerminal(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        synchronized(textBuffer) {
            var yOffset = height.toFloat() - 50f // Start drawing from the bottom
            
            // Draw lines bottom-to-top to mimic standard terminal scrolling
            for (i in textBuffer.indices.reversed()) {
                if (yOffset < 0) break // Stop drawing if it's off-screen
                canvas.drawText(textBuffer[i], 20f, yOffset, textPaint)
                yOffset -= (textPaint.textSize + 10f) // Line height spacing
            }
        }
    }
}
