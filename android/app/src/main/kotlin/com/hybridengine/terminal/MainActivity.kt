package com.hybridengine.terminal

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private var terminalService: TerminalService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TerminalService.TerminalBinder
            terminalService = localBinder?.service
            isBound = true
            
            // Connect broker output stream directly to the terminal surface
            terminalService?.broker?.outputListener = { output ->
                runOnUiThread {
                    terminalSurface.appendOutput(output)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Request Runtime Permissions (Storage & Notifications)
        requestRequiredPermissions()

        // 2. Initialize Fullscreen Terminal View
        terminalSurface = findViewById(R.id.terminal_surface)

        // 3. Start & Bind to Persistent TerminalService (manages VM and Broker lifecycle)
        TerminalService.start(this)
        val serviceIntent = Intent(this, TerminalService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 4. Connect Native Inline Terminal Input directly to the Service Broker
        terminalSurface.onCommandSubmitted = { command ->
            val trimmed = command.trim()
            if (trimmed.isNotEmpty()) {
                terminalService?.broker?.send(trimmed)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            terminalService?.broker?.outputListener = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestRequiredPermissions() {
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Storage Management Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
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
