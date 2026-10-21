package com.hybridengine.terminal.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * CronBroadcastReceiver: Handles scheduled cron task execution via AlarmManager.
 * Wakes the device exactly at the scheduled time and executes the registered command.
 */
class CronBroadcastReceiver : BroadcastReceiver() {

    private val TAG = "CronBroadcastReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val taskName = intent?.getStringExtra("task_name") ?: return
        val command = intent?.getStringExtra("command") ?: return

        Log.i(TAG, "Cron task triggered: $taskName - executing: $command")

        try {
            // Ensure WakLock is held during command execution so the CPU stays awake
            // during Doze mode for the duration of the command
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock: PowerManager.WakeLock? = if (powerManager != null) {
                val wk = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "VoidTerm:Cron:$taskName"
                )
                wk.apply { acquire() }
                wk
            }

            // Execute the command via the terminal service
            // Use the existing DynamicCoreLoader/TerminalService command execution pipeline
            val execIntent = Intent(context, TerminalService::class.java).apply {
                action = com.hybridengine.terminal.TerminalService.ACTION_EXECUTE_COMMAND
                putExtra(com.hybridengine.terminal.TerminalService.EXTRA_COMMAND, command)
            }

            // Start service to process the command
            context.startService(execIntent)

            // Release waklock after short delay to allow command output to be captured
            java.util.Timer().also { timer ->
                timer.schedule(
                    java.util.Task(null, wakeLock),
                    5000 // 5 second grace period
                )
            }
        }

        // Inner class for TimerTask - releases wakelock
        private class Task(val wakeLock: PowerManager.WakeLock?, val context: Context?) : java.util.TimerTask() {
            override fun run() {
                wakeLock?.release()
                if (context != null) {
                    // Stop the service after task completion
                    val stopIntent = Intent(context, CronBroadcastReceiver::class.java)
                    context.stopService(stopIntent)
                }
            }
        }
    }
}