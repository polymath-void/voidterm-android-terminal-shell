package com.hybridengine.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * ReloadCoreReceiver: Listens for com.hybridengine.terminal.RELOAD_CORE broadcasts
 * to trigger dynamic reloading of usr/bin/ scripts, configurations, and terminal UI styles.
 */
class ReloadCoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action != ACTION_RELOAD_CORE) return

        Log.i(TAG, "Received RELOAD_CORE intent. Hot-reloading VoidTerm runtime...")
        DynamicCoreLoader.reload(context)

        TerminalService.instance?.broker?.emitOutput(
            "\r\n\u001b[32m✔ [VoidTerm] Runtime scripts and configuration reloaded dynamically.\u001b[0m\r\n"
        )

        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, "VoidTerm: Dynamic Core Reloaded", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "ReloadCoreReceiver"
        const val ACTION_RELOAD_CORE = "com.hybridengine.terminal.RELOAD_CORE"
    }
}
