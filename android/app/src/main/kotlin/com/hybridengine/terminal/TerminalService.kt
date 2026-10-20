package com.hybridengine.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class TerminalService : Service() {

    private val binder = TerminalBinder()
    lateinit var vmManager: VmManager
        private set
    lateinit var broker: Broker
        private set
    lateinit var voidFS: VoidFS
        private set

    private var isEngineStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    inner class TerminalBinder : Binder() {
        val service: TerminalService
            get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TerminalService created.")
        instance = this

        // 1. Acquire Partial WakeLock to prevent CPU throttling during background processes
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoidTerm:ServiceWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours ceiling
            }
            Log.i(TAG, "Acquired partial WakeLock for terminal service.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring WakeLock: ${e.message}")
        }

        // 2. Initialize Dynamic Core Loader and VoidFS
        DynamicCoreLoader.initialize(this)
        voidFS = VoidFS(this)
        voidFS.setupFilesystem()

        // 3. Initialize Broker and LocalProcessSession
        broker = Broker(this)
        val session = LocalProcessSession(this, voidFS) { output ->
            broker.onTerminalOutput(output)
        }
        broker.localSession = session

        vmManager = VmManager(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_SHUTDOWN_VM) {
            Log.i(TAG, "Shutdown action received from notification. Stopping microVM and service.")
            shutdownEngine()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!isEngineStarted) {
            isEngineStarted = true
            startEngine()
        }

        if (action == ACTION_EXECUTE_COMMAND) {
            val cmd = intent.getStringExtra(EXTRA_COMMAND)
            if (!cmd.isNullOrBlank()) {
                Log.i(TAG, "Executing command from start service intent: $cmd")
                executeCommand(cmd)
            }
        }

        return START_STICKY
    }

    fun executeCommand(command: String) {
        if (::broker.isInitialized) {
            broker.send(command)
        }
    }

    private fun startEngine() {
        Thread {
            try {
                // 1. Stage Debian rootfs if needed (for AVF VM when supported)
                try {
                    OsInstaller(this).installIfNeeded()
                } catch (e: Throwable) {
                    Log.w(TAG, "OsInstaller notice: ${e.message}")
                }

                // 2. Boot AVF microVM (falls back silently if AVF pVM not enabled)
                try {
                    vmManager.startLiteLinuxVm(broker)
                } catch (e: Throwable) {
                    Log.w(TAG, "MicroVM init notice: ${e.message}")
                }

                // 3. Start Broker & Local Process Session
                broker.start()
                Log.i(TAG, "VoidTerm engine and local shell session initialized in foreground service.")
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting VoidTerm engine in service", e)
            }
        }.start()
    }

    private fun startForegroundNotification() {
        val channelId = CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VoidTerm Linux MicroVM Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status and control for the VoidTerm AVF MicroVM and Tokio Broker"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }

        // Tap notification to open MainActivity
        val openActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action button to Shutdown VM
        val shutdownIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_SHUTDOWN_VM
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            1,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VoidTerm Linux Shell Host Active")
            .setContentText("Dynamic POSIX core & VoidFS active")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Shutdown Host",
                shutdownPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun shutdownEngine() {
        try {
            broker.localSession?.destroy()
        } catch (_: Throwable) {}

        try {
            vmManager.stopVm()
            Log.i(TAG, "MicroVM stopped.")
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping microVM: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TerminalService destroyed.")
        instance = null

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wakeLock: ${e.message}")
        }

        try {
            broker.localSession?.destroy()
        } catch (_: Throwable) {}

        try {
            vmManager.stopVm()
        } catch (_: Throwable) {}
    }

    companion object {
        const val TAG = "VoidTerm-Service"
        const val CHANNEL_ID = "voidterm_vm_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVICE = "com.hybridengine.terminal.ACTION_START_SERVICE"
        const val ACTION_SHUTDOWN_VM = "com.hybridengine.terminal.ACTION_SHUTDOWN_VM"
        const val ACTION_EXECUTE_COMMAND = "com.hybridengine.terminal.ACTION_EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "extra_command"

        @Volatile
        var instance: TerminalService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun execute(context: Context, command: String) {
            val running = instance
            if (running != null) {
                running.executeCommand(command)
            } else {
                val intent = Intent(context, TerminalService::class.java).apply {
                    action = ACTION_EXECUTE_COMMAND
                    putExtra(EXTRA_COMMAND, command)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_SHUTDOWN_VM
            }
            context.startService(intent)
        }
    }
}
