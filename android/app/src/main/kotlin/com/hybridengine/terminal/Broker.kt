package com.hybridengine.terminal

class Broker(private val terminalView: TerminalSurfaceView) {
    
    init {
        System.loadLibrary("hybrid_term_broker")
    }

    external fun startDaemon()
    external fun sendCommand(command: String)

    fun onTerminalOutput(output: String) {
        // Push the Rust stdout directly into the SurfaceView drawing buffer
        terminalView.appendOutput(output)
    }
}
