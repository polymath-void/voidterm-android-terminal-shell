package com.hybridengine.terminal.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * TelemetrySyncReceiver: Handles bi-daily AlarmManager alarms at 00:01 and 12:01.
 * Wakes up briefly to sync extension durations and reschedule next occurrences.
 */
class TelemetrySyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == NodeOsTelemetrySync.ACTION_SYNC_TELEMETRY) {
            Log.i(TAG, "Bi-daily telemetry sync broadcast received")

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VoidTerm:TelemetrySyncWakeLock"
            )

            try {
                wakeLock?.acquire(30_000L) // 30-second ceiling
                val sync = NodeOsTelemetrySync(context)
                // Clean up any dead zombie PIDs and reschedule
                sync.getAllRunningExtensions()
                sync.setupAlarmManagerSync()
                Log.i(TAG, "Bi-daily telemetry check and alarm reschedule completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error in TelemetrySyncReceiver: ${e.message}", e)
            } finally {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            }
        }
    }

    companion object {
        private const val TAG = "TelemetrySyncReceiver"
    }
}
