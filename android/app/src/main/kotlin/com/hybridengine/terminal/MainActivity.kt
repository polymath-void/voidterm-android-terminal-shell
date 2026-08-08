package com.hybridengine.terminal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private lateinit var broker: Broker
    private lateinit var commandInput: EditText
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Verify and Request File System Access before booting engines
        requestStoragePermissions()

        // 2. Initialize Views
        terminalSurface = findViewById(R.id.terminal_surface)
        commandInput = findViewById(R.id.command_input)
        btnSend = findViewById(R.id.btn_send)

        // 3. Initialize the JNI Broker and start the background daemon
        broker = Broker(terminalSurface)
        broker.startDaemon()

        // 4. Setup Input Listeners
        btnSend.setOnClickListener {
            dispatchCommand()
        }

        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                dispatchCommand()
                true
            } else {
                false
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        } else {
            // Android 10 and below fallback
        }
    }

    private fun dispatchCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isNotEmpty()) {
            // Echo the command to the screen visually
            terminalSurface.appendOutput("\nuser@hybrid-engine:~$ $command")
            
            // Push the command down through the JNI bridge to the Rust Tokio multiplexer
            broker.sendCommand(command)
            
            // Clear the input field for the next command
            commandInput.text.clear()
        }
    }
}
