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
import android.util.Log
import androidx.core.app.NotificationCompat

class TerminalService : Service() {

    private val binder = TerminalBinder()
    lateinit var vmManager: VmManager
        private set
    lateinit var broker: Broker
        private set

    private var isEngineStarted = false

    inner class TerminalBinder : Binder() {
        val service: TerminalService
            get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TerminalService created.")
        vmManager = VmManager(this)
        broker = Broker()
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

        return START_STICKY
    }

    private fun startEngine() {
        Thread {
            try {
                // 1. Stage Debian rootfs if needed
                OsInstaller(this).installIfNeeded()

                // 2. Boot AVF microVM
                vmManager.startLiteLinuxVm(broker)

                // 3. Start Rust daemon multiplexer
                broker.start()
                Log.i(TAG, "VoidTerm engine and microVM initialized in foreground service.")
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
            .setContentTitle("VoidTerm Linux MicroVM Active")
            .setContentText("Debian ARM64 Guest running via AVF vsock")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Shutdown VM",
                shutdownPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun shutdownEngine() {
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

        fun stop(context: Context) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_SHUTDOWN_VM
            }
            context.startService(intent)
        }
    }
}
