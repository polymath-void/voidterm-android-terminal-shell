package com.hybridengine.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * RunCommandReceiver: Handles external command dispatching intents compatible with
 * both VoidTerm and Termux RUN_COMMAND protocols.
 */
class RunCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action != ACTION_RUN_COMMAND && action != ACTION_TERMUX_RUN_COMMAND) {
            return
        }

        Log.i(TAG, "Received command intent with action: $action")

        // 1. Direct command extraction (e.g. from adb or Termux am broadcast)
        val directCmd = intent.getStringExtra("command") ?: intent.getStringExtra("cmd")
        val finalCommand: String

        if (!directCmd.isNullOrBlank()) {
            finalCommand = directCmd
        } else {
            // 2. Structured Termux-compatible RUN_COMMAND intent extras
            val path = intent.getStringExtra(EXTRA_RUN_COMMAND_PATH)
                ?: intent.getStringExtra(EXTRA_TERMUX_RUN_COMMAND_PATH)
                ?: return

            val arguments = intent.getStringArrayExtra(EXTRA_RUN_COMMAND_ARGUMENTS)
                ?: intent.getStringArrayExtra(EXTRA_TERMUX_RUN_COMMAND_ARGUMENTS)
                ?: emptyArray()

            val workDir = intent.getStringExtra(EXTRA_RUN_COMMAND_WORKDIR)
                ?: intent.getStringExtra(EXTRA_TERMUX_RUN_COMMAND_WORKDIR)

            val cmdBuilder = StringBuilder()
            if (!workDir.isNullOrBlank()) {
                cmdBuilder.append("cd \"").append(workDir).append("\" && ")
            }
            cmdBuilder.append(path)
            for (arg in arguments) {
                cmdBuilder.append(" \"").append(arg).append("\"")
            }
            finalCommand = cmdBuilder.toString()
        }

        Log.i(TAG, "Dispatching command to TerminalService: $finalCommand")
        TerminalService.execute(context, finalCommand)
    }

    companion object {
        private const val TAG = "RunCommandReceiver"

        const val ACTION_RUN_COMMAND = "com.hybridengine.terminal.RUN_COMMAND"
        const val ACTION_TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"

        const val EXTRA_RUN_COMMAND_PATH = "com.hybridengine.terminal.RUN_COMMAND_PATH"
        const val EXTRA_TERMUX_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"

        const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.hybridengine.terminal.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_TERMUX_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"

        const val EXTRA_RUN_COMMAND_WORKDIR = "com.hybridengine.terminal.RUN_COMMAND_WORKDIR"
        const val EXTRA_TERMUX_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    }
}
