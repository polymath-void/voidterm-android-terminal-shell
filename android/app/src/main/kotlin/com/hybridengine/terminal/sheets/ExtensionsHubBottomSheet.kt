package com.hybridengine.terminal.sheets

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hybridengine.terminal.MainActivity
import com.hybridengine.terminal.R
import com.hybridengine.terminal.extensions.ExtensionManager
import com.hybridengine.terminal.extensions.ExtensionModel

/**
 * ExtensionsHubBottomSheet: Interactive UI dashboard for installed polyglot extensions,
 * real-time PID monitoring, single-extension force kill, and master kill-all safety breaker.
 */
class ExtensionsHubBottomSheet : BottomSheetDialogFragment() {

    private var listContainer: LinearLayout? = null
    private var tvStatus: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_extensions_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listContainer = view.findViewById(R.id.extensions_list_container)
        tvStatus = view.findViewById(R.id.tv_hub_status)

        val btnKillAll = view.findViewById<TextView>(R.id.btn_kill_all_extensions)
        btnKillAll.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF5555"))
                cornerRadius = 8f * resources.displayMetrics.density
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showKillAllConfirmation()
            }
        }

        refreshExtensionList()
    }

    fun refreshExtensionList() {
        val container = listContainer ?: return
        val ctx = context ?: return
        container.removeAllViews()

        val extensions = ExtensionManager.loadInstalledExtensions(ctx)
        val runningCount = extensions.count { it.isRunning }
        tvStatus?.text = "$runningCount Running  |  ${extensions.size} Installed"

        if (extensions.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "No extensions installed in \$PREFIX/etc/extensions/"
                setTextColor(Color.parseColor("#6272A4"))
                textSize = 14f
                setPadding(0, 32, 0, 32)
            }
            container.addView(emptyTv)
            return
        }

        val density = resources.displayMetrics.density

        for (ext in extensions) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#282A36"))
                    cornerRadius = 8f * density
                    setStroke((1 * density).toInt(), Color.parseColor("#44475A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (8 * density).toInt())
                }
            }

            // Row 1: Title + Runtime Badge + Action Button
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val titleTv = TextView(ctx).apply {
                text = ext.name
                setTextColor(Color.parseColor("#F8F8F2"))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val runtimeBadge = TextView(ctx).apply {
                text = if (ext.runtime == "python") "🐍 Python" else "⚡ Node.js"
                val badgeColor = if (ext.runtime == "python") "#50FA7B" else "#F1FA8C"
                setTextColor(Color.parseColor(badgeColor))
                textSize = 11f
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#21222C"))
                    cornerRadius = 4f * density
                }
            }

            // Action Button (Launch or Kill)
            val actionBtn = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * density).toInt()
                }
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)

                if (ext.isRunning) {
                    text = "KILL"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FF5555"))
                        cornerRadius = 4f * density
                    }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val killed = ExtensionManager.killExtension(ctx, ext.id)
                        if (killed) {
                            Toast.makeText(ctx, "Killed ${ext.name} (PID ${ext.activePid})", Toast.LENGTH_SHORT).show()
                        }
                        refreshExtensionList()
                    }
                } else {
                    text = "LAUNCH"
                    setTextColor(Color.parseColor("#282A36"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#50FA7B"))
                        cornerRadius = 4f * density
                    }
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        (activity as? MainActivity)?.let { mainAct ->
                            val activeTab = mainAct.terminalService?.sessionManager?.getActiveSession()
                            if (activeTab != null) {
                                ExtensionManager.executeSlashCommand(ctx, activeTab, "/${ext.id}")
                            }
                        }
                        dismiss()
                    }
                }
            }

            topRow.addView(titleTv)
            topRow.addView(runtimeBadge)
            topRow.addView(actionBtn)

            // Row 2: Status & Description
            val descTv = TextView(ctx).apply {
                text = ext.description.ifEmpty { "Entry: ${ext.entry}" }
                setTextColor(Color.parseColor("#6272A4"))
                textSize = 12f
                setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
            }

            val statusTv = TextView(ctx).apply {
                if (ext.isRunning) {
                    text = "● RUNNING (PID: ${ext.activePid})"
                    setTextColor(Color.parseColor("#FF5555"))
                } else {
                    text = "○ IDLE"
                    setTextColor(Color.parseColor("#6272A4"))
                }
                textSize = 11f
            }

            card.addView(topRow)
            card.addView(descTv)
            card.addView(statusTv)
            container.addView(card)
        }
    }

    private fun showKillAllConfirmation() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("⚡ Kill All Extensions?")
            .setMessage("This will forcefully terminate all running extension processes and background jobs immediately.")
            .setPositiveButton("KILL ALL") { _, _ ->
                val count = ExtensionManager.killAllExtensions(ctx)
                Toast.makeText(ctx, "Terminated $count active extension(s)", Toast.LENGTH_SHORT).show()
                refreshExtensionList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val TAG = "ExtensionsHubBottomSheet"
    }
}
