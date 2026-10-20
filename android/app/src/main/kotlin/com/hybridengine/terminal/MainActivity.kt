package com.hybridengine.terminal

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private var terminalService: TerminalService? = null
    private var isBound = false

    // Agent HUD Views
    private lateinit var cardAgentHud: MaterialCardView
    private lateinit var textHudBadge: TextView
    private lateinit var textHudAgentName: TextView
    private lateinit var textHudThought: TextView
    private lateinit var textHudFile: TextView
    private lateinit var progressHud: ProgressBar
    private lateinit var btnHudDismiss: ImageView
    private lateinit var fabVoice: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var voiceManager: VoiceManager
    private val hudDismissHandler = Handler(Looper.getMainLooper())
    private var hudDismissRunnable: Runnable? = null

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

            // Connect broker HUD telemetry directly to the agent HUD overlay
            terminalService?.broker?.hudTelemetryListener = { telemetry ->
                runOnUiThread {
                    updateAgentHud(telemetry)
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

        // 1. Request Runtime Permissions (Storage, Notifications & Microphone)
        requestRequiredPermissions()

        // 2. Initialize Fullscreen Terminal View & HUD Overlay
        terminalSurface = findViewById(R.id.terminal_surface)
        cardAgentHud = findViewById(R.id.card_agent_hud)
        textHudBadge = findViewById(R.id.text_hud_badge)
        textHudAgentName = findViewById(R.id.text_hud_agent_name)
        textHudThought = findViewById(R.id.text_hud_thought)
        textHudFile = findViewById(R.id.text_hud_file)
        progressHud = findViewById(R.id.progress_hud)
        btnHudDismiss = findViewById(R.id.btn_hud_dismiss)
        fabVoice = findViewById(R.id.fab_voice)

        btnHudDismiss.setOnClickListener {
            cardAgentHud.visibility = View.GONE
        }

        // Wire FloatingActionButton to open SettingsActivity
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Initialize Live Voice Interaction with Agent
        voiceManager = VoiceManager(this).apply {
            onListeningStateChanged = { listening ->
                runOnUiThread {
                    if (listening) {
                        fabVoice.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5555"))
                        updateAgentHud(
                            HudTelemetry(
                                agent = "Voice Agent",
                                status = "listening",
                                thought = "🎙️ Listening to live voice command..."
                            )
                        )
                    } else {
                        fabVoice.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6272A4"))
                    }
                }
            }

            onSpeechPartial = { partial ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Live Voice Input",
                            status = "listening",
                            thought = "“$partial”"
                        )
                    )
                }
            }

            onSpeechResult = { recognizedText ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Voice Agent",
                            status = "executing",
                            thought = "Spoken Directive: \"$recognizedText\""
                        )
                    )
                    // Submit spoken command directly to terminal broker
                    terminalService?.broker?.send(recognizedText)
                }
            }

            onSpeechError = { errorMsg ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Voice Agent",
                            status = "error",
                            thought = errorMsg
                        )
                    )
                }
            }
        }

        fabVoice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            } else {
                voiceManager.toggleListening()
            }
        }

        // 3. Start & Bind to Persistent TerminalService (manages VM and Broker lifecycle)
        TerminalService.start(this)
        val serviceIntent = Intent(this, TerminalService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 4. Connect Native Inline Terminal Input directly to the Service Broker
        terminalSurface.onCommandSubmitted = { command ->
            val trimmed = command.trim()
            if (trimmed.isNotEmpty()) {
                terminalSurface.appendOutput("\u001b[32m$ $trimmed\u001b[0m\r\n")
                terminalService?.broker?.send(trimmed)
            }
        }

        // 5. Connect Dynamic Config and Resource Reloader to Terminal UI
        DynamicCoreLoader.onConfigReloaded = {
            runOnUiThread {
                if (::terminalSurface.isInitialized) {
                    terminalSurface.reloadConfig()
                }
            }
        }
    }

    private fun updateAgentHud(telemetry: HudTelemetry) {
        hudDismissRunnable?.let { hudDismissHandler.removeCallbacks(it) }

        cardAgentHud.alpha = 1f
        cardAgentHud.visibility = View.VISIBLE
        textHudAgentName.text = telemetry.agent
        textHudThought.text = telemetry.thought

        when (telemetry.status.lowercase()) {
            "working", "executing", "running" -> {
                textHudBadge.text = "● RUNNING"
                textHudBadge.setTextColor(Color.parseColor("#50FA7B"))
            }
            "thinking", "reasoning" -> {
                textHudBadge.text = "● THINKING"
                textHudBadge.setTextColor(Color.parseColor("#FFB86C"))
            }
            "completed", "success" -> {
                textHudBadge.text = "✔ COMPLETED"
                textHudBadge.setTextColor(Color.parseColor("#8BE9FD"))
            }
            "error", "failed" -> {
                textHudBadge.text = "✖ ERROR"
                textHudBadge.setTextColor(Color.parseColor("#FF5555"))
            }
            else -> {
                textHudBadge.text = "● ${telemetry.status.uppercase()}"
                textHudBadge.setTextColor(Color.parseColor("#BD93F9"))
            }
        }

        if (!telemetry.activeFile.isNullOrEmpty()) {
            textHudFile.text = "📄 ${telemetry.activeFile}"
            textHudFile.visibility = View.VISIBLE
        } else {
            textHudFile.visibility = View.GONE
        }

        if (telemetry.progress != null) {
            progressHud.visibility = View.VISIBLE
            progressHud.isIndeterminate = false
            progressHud.progress = (telemetry.progress * 100).toInt().coerceIn(0, 100)
        } else if (telemetry.status.lowercase() in listOf("working", "thinking", "running", "executing")) {
            progressHud.visibility = View.VISIBLE
            progressHud.isIndeterminate = true
        } else {
            progressHud.visibility = View.GONE
        }

        // Auto-dismiss completed or idle notifications after 4 seconds
        if (telemetry.status.lowercase() in listOf("completed", "idle", "success")) {
            val runnable = Runnable {
                cardAgentHud.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        cardAgentHud.visibility = View.GONE
                        cardAgentHud.alpha = 1f
                    }
                    .start()
            }
            hudDismissRunnable = runnable
            hudDismissHandler.postDelayed(runnable, 4000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::terminalSurface.isInitialized) {
            terminalSurface.reloadConfig()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hudDismissRunnable?.let { hudDismissHandler.removeCallbacks(it) }
        try {
            voiceManager.destroy()
        } catch (_: Exception) {}
        if (isBound) {
            terminalService?.broker?.outputListener = null
            terminalService?.broker?.hudTelemetryListener = null
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestRequiredPermissions() {
        // Microphone permission for live voice agent interaction
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
        }

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
