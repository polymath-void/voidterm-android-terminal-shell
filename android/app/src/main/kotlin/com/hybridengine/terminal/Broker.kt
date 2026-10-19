package com.hybridengine.terminal

import android.util.Log
import org.json.JSONObject

data class HudTelemetry(
    val agent: String,
    val status: String,
    val thought: String,
    val activeFile: String? = null,
    val progress: Float? = null
)

class Broker(var outputListener: ((String) -> Unit)? = null) {
    
    private var isNativeLoaded = false
    var hudTelemetryListener: ((HudTelemetry) -> Unit)? = null

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
        if (output.startsWith("__HUD_TELEMETRY__:")) {
            val jsonStr = output.removePrefix("__HUD_TELEMETRY__:")
            try {
                val obj = JSONObject(jsonStr)
                val telemetry = HudTelemetry(
                    agent = obj.optString("agent", "Agent"),
                    status = obj.optString("status", "working"),
                    thought = obj.optString("thought", ""),
                    activeFile = if (obj.has("active_file") && !obj.isNull("active_file")) obj.getString("active_file") else null,
                    progress = if (obj.has("progress") && !obj.isNull("progress")) obj.getDouble("progress").toFloat() else null
                )
                hudTelemetryListener?.invoke(telemetry)
                return
            } catch (e: Exception) {
                Log.w("VoidTerm", "Failed to parse HUD telemetry: ${e.message}")
            }
        }
        emitOutput(output)
    }

    private fun emitOutput(text: String) {
        outputListener?.invoke(text)
    }
}
