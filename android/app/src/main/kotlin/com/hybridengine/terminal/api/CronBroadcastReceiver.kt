package com.hybridengine.terminal.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.hybridengine.terminal.TerminalService
import java.util.Timer
import java.util.TimerTask

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
            // Ensure WakeLock is held during command execution so the CPU stays awake
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VoidTerm:Cron:$taskName"
            )?.apply {
                acquire(10000L) // 10 second safety ceiling
            }

            // Execute the command via TerminalService
            TerminalService.execute(context, command)

            // Release wakelock after short grace period to allow command execution to start
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release wakeLock", e)
                    }
                }
            }, 5000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing scheduled cron task", e)
        }
    }
}
