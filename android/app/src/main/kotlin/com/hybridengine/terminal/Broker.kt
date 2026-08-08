package com.hybridengine.terminal

import android.util.Log

class Broker(var outputListener: ((String) -> Unit)? = null) {
    
    private var isNativeLoaded = false

    constructor(terminalView: TerminalSurfaceView) : this({ terminalView.appendOutput(it) })

    init {
        try {
            System.loadLibrary("hybrid_term_broker")
            isNativeLoaded = true
            Log.i("VoidTerm", "libhybrid_term_broker.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("VoidTerm", "libhybrid_term_broker.so not found or failed to load", e)
        } catch (e: Exception) {
            Log.e("VoidTerm", "Unexpected error loading native library", e)
        }
    }

    fun start() {
        if (isNativeLoaded) {
            try {
                startDaemon()
                emitOutput("🚀 VoidTerm Shell Terminal v0.1.0-alpha")
                emitOutput("📡 Hybrid Term Broker: Native Tokio multiplexer active.")
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Failed to start native daemon", e)
                emitOutput("⚠️ Native daemon start failed: ${e.message}")
            }
        } else {
            emitOutput("🚀 VoidTerm Shell Terminal v0.1.0-alpha")
            emitOutput("📡 Standalone Terminal Mode (Native Broker pending).")
            emitOutput("Type commands below to interact.")
        }
    }

    fun send(command: String) {
        if (isNativeLoaded) {
            try {
                sendCommand(command)
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Error sending command", e)
                emitOutput("⚠️ Error executing command: ${e.message}")
            }
        } else {
            emitOutput("Executed: $command")
        }
    }

    fun provisionDisk(diskPath: String, rootfsDir: String) {
        if (isNativeLoaded) {
            try {
                provisionDiskNative(diskPath, rootfsDir)
            } catch (e: Throwable) {
                Log.e("VoidTerm", "Error provisioning disk: ${e.message}", e)
            }
        } else {
            Log.w("VoidTerm", "Cannot provision disk: native broker library not loaded.")
        }
    }

    private external fun startDaemon()
    private external fun sendCommand(command: String)
    @Suppress("FunctionName")
    private external fun provisionDiskNative(diskPath: String, rootfsDir: String)

    fun onTerminalOutput(output: String) {
        emitOutput(output)
    }

    private fun emitOutput(text: String) {
        outputListener?.invoke(text)
    }
}
