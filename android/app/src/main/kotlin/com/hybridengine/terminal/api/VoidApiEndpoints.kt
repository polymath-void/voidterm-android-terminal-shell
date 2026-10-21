package com.hybridengine.terminal.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hybridengine.terminal.MainActivity
import com.hybridengine.terminal.extensions.ExtensionManager
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * VoidApiEndpoints: JSON-RPC dispatcher for embedded Android OS automations.
 * Handles notification, clipboard, toast, vibrator, battery, wakelock, intent, and process kill.
 */
object VoidApiEndpoints {

    private const val TAG = "VoidApiEndpoints"
    private val activeWakeLocks = ConcurrentHashMap<String, PowerManager.WakeLock>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun handleMethod(method: String, params: JSONObject, context: Context): JSONObject {
        return try {
            when (method) {
                "notification.post" -> handleNotificationPost(params, context)
                "notification.cancel" -> handleNotificationCancel(params, context)
                "clipboard.get" -> handleClipboardGet(context)
                "clipboard.set" -> handleClipboardSet(params, context)
                "toast.show" -> handleToastShow(params, context)
                "vibrator.vibrate" -> handleVibrate(params, context)
                "battery.status" -> handleBatteryStatus(context)
                "power.wakelock_acquire" -> handleWakeLockAcquire(params, context)
                "power.wakelock_release" -> handleWakeLockRelease(params)
                "intent.launch" -> handleIntentLaunch(params, context)
                "intent.view_url" -> handleViewUrl(params, context)
                "process.kill" -> handleProcessKill(params, context)
                "process.kill_all" -> handleProcessKillAll(context)
                else -> JSONObject().put("error", "Unknown method: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $method: ${e.message}", e)
            JSONObject().put("error", e.message ?: "Execution failed")
        }
    }

    private fun handleNotificationPost(params: JSONObject, context: Context): JSONObject {
        val title = params.optString("title", "VoidTerm")
        val text = params.optString("text", "")
        val channelId = params.optString("channel_id", "voidterm_notifications")
        val notificationId = params.optInt("id", 1002)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VoidTerm Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "VoidTerm Extension Notifications"
            }
            manager?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        manager?.notify(notificationId, builder.build())
        return JSONObject().put("status", "posted").put("id", notificationId)
    }

    private fun handleNotificationCancel(params: JSONObject, context: Context): JSONObject {
        val id = params.optInt("id", 1002)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(id)
        return JSONObject().put("status", "cancelled").put("id", id)
    }

    private fun handleClipboardGet(context: Context): JSONObject {
        var text = ""
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                text = clip.getItemAt(0)?.text?.toString() ?: ""
            }
        }
        // Read directly if available
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            text = clip.getItemAt(0)?.text?.toString() ?: ""
        }
        return JSONObject().put("text", text)
    }

    private fun handleClipboardSet(params: JSONObject, context: Context): JSONObject {
        val text = params.optString("text", "")
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("voidterm", text))
        }
        return JSONObject().put("status", "set").put("length", text.length)
    }

    private fun handleToastShow(params: JSONObject, context: Context): JSONObject {
        val text = params.optString("text", "")
        val duration = if (params.optBoolean("long", false)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        mainHandler.post {
            Toast.makeText(context, text, duration).show()
        }
        return JSONObject().put("status", "shown")
    }

    private fun handleVibrate(params: JSONObject, context: Context): JSONObject {
        val durationMs = params.optLong("duration", 200L)
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
        return JSONObject().put("status", "vibrated").put("duration_ms", durationMs)
    }

    private fun handleBatteryStatus(context: Context): JSONObject {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return JSONObject().apply {
            put("percentage", percentage)
            put("is_charging", isCharging)
            put("temperature_c", temp / 10.0)
            put("plugged", status)
        }
    }

    private fun handleWakeLockAcquire(params: JSONObject, context: Context): JSONObject {
        val tag = params.optString("tag", "VoidTerm:ExtensionLock")
        val timeoutSec = params.optLong("timeout", 300L)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return JSONObject().put("error", "PowerManager unavailable")
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            acquire(timeoutSec * 1000L)
        }
        activeWakeLocks[tag] = lock
        Log.i(TAG, "WakeLock acquired for tag: $tag (timeout: ${timeoutSec}s)")
        return JSONObject().put("status", "acquired").put("tag", tag)
    }

    private fun handleWakeLockRelease(params: JSONObject): JSONObject {
        val tag = params.optString("tag", "VoidTerm:ExtensionLock")
        val lock = activeWakeLocks.remove(tag)
        if (lock != null && lock.isHeld) {
            lock.release()
            Log.i(TAG, "WakeLock released for tag: $tag")
            return JSONObject().put("status", "released").put("tag", tag)
        }
        return JSONObject().put("status", "not_held").put("tag", tag)
    }

    fun releaseAllWakeLocks() {
        for ((tag, lock) in activeWakeLocks) {
            try {
                if (lock.isHeld) lock.release()
            } catch (_: Exception) {}
        }
        activeWakeLocks.clear()
        Log.i(TAG, "All active WakeLocks released")
    }

    fun releaseWakeLocksByPrefix(prefix: String) {
        val it = activeWakeLocks.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.startsWith(prefix)) {
                try {
                    if (entry.value.isHeld) entry.value.release()
                } catch (_: Exception) {}
                it.remove()
            }
        }
    }

    private fun handleIntentLaunch(params: JSONObject, context: Context): JSONObject {
        val action = params.optString("action", Intent.ACTION_VIEW)
        val uriStr = params.optString("uri", "")
        val intent = if (uriStr.isNotEmpty()) Intent(action, Uri.parse(uriStr)) else Intent(action)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("status", "launched").put("action", action)
    }

    private fun handleViewUrl(params: JSONObject, context: Context): JSONObject {
        val url = params.optString("url", "")
        if (url.isEmpty()) return JSONObject().put("error", "URL parameter required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return JSONObject().put("status", "opened").put("url", url)
    }

    private fun handleProcessKill(params: JSONObject, context: Context): JSONObject {
        val extId = params.optString("ext_id", "")
        if (extId.isEmpty()) return JSONObject().put("error", "ext_id parameter required")
        val killed = ExtensionManager.killExtension(context, extId)
        return JSONObject().put("status", if (killed) "killed" else "not_running").put("ext_id", extId)
    }

    private fun handleProcessKillAll(context: Context): JSONObject {
        val count = ExtensionManager.killAllExtensions(context)
        return JSONObject().put("status", "killed_all").put("count", count)
    }
}
