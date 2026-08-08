package com.hybridengine.terminal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private lateinit var broker: Broker
    private lateinit var vmManager: VmManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Request Storage Permissions safely
        try {
            requestStoragePermissions()
        } catch (e: Exception) {
            Log.w("VoidTerm", "Storage permission request error: ${e.message}")
        }

        // 2. Initialize Fullscreen Terminal View
        terminalSurface = findViewById(R.id.terminal_surface)

        // 3. Extract bundled Debian rootfs if needed (Step 1: OsInstaller first)
        try {
            OsInstaller(this).installIfNeeded()
        } catch (e: Exception) {
            Log.w("VoidTerm", "OsInstaller initialization notice: ${e.message}")
        }

        // 4. Initialize and boot the hardware-accelerated Linux VM (AVF) (Step 2: VmManager second)
        vmManager = VmManager(this)
        vmManager.startLiteLinuxVm()

        // 5. Initialize the Broker & start native engine (Step 3: Broker last)
        broker = Broker(terminalSurface)
        broker.start()

        // 6. Connect Native Inline Terminal Input directly to the Broker
        terminalSurface.onCommandSubmitted = { command ->
            val trimmed = command.trim()
            if (trimmed.isNotEmpty()) {
                broker.send(trimmed)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure we gracefully kill the microVM to free up device RAM
        vmManager.stopVm()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        Log.w("VoidTerm", "Could not launch manage storage intent: ${ex.message}")
                    }
                }
            }
        }
    }
}
