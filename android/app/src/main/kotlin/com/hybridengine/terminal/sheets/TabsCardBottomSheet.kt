package com.hybridengine.terminal.sheets

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hybridengine.terminal.R
import com.hybridengine.terminal.SessionManager
import com.hybridengine.terminal.TabSession

/**
 * TabsCardBottomSheet: Visual card overview for multi-tab terminal sessions.
 * Displays each tab's ID, custom title, CWD, process running state, and preview.
 */
class TabsCardBottomSheet : BottomSheetDialogFragment() {

    private var sessionManager: SessionManager? = null

    fun show(fragmentManager: FragmentManager, sessionManager: SessionManager) {
        this.sessionManager = sessionManager
        show(fragmentManager, TAG)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1F29"))
            setPadding(32, 24, 32, 32)
        }

        val density = resources.displayMetrics.density

        // Drag handle
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#44475A"))
                cornerRadius = 2f * density
            }
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * density).toInt()
            }
        }
        root.addView(handle)

        // Title row
        val titleTv = TextView(context).apply {
            text = "⊞ Active Terminal Tabs"
            setTextColor(Color.parseColor("#F8F8F2"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(titleTv)

        val sm = sessionManager
        if (sm == null || sm.sessions.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "No active tabs"
                setTextColor(Color.parseColor("#6272A4"))
                textSize = 14f
            }
            root.addView(emptyTv)
            return root
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (380 * density).toInt()
            )
        }

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val activeIndex = sm.activeIndex

        for ((index, tab) in sm.sessions.withIndex()) {
            val isActive = index == activeIndex

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(if (isActive) Color.parseColor("#282A36") else Color.parseColor("#21222C"))
                    cornerRadius = 8f * density
                    setStroke(
                        (if (isActive) 2 * density else 1 * density).toInt(),
                        if (isActive) Color.parseColor("#50FA7B") else Color.parseColor("#44475A")
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                isClickable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sm.switchToSession(index)
                    dismiss()
                }
            }

            // Top Header: Title & Close [x]
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(context).apply {
                val pinBadge = if (tab.isPinned) "📌 " else ""
                val activeBadge = if (isActive) "● " else ""
                text = "$pinBadge$activeBadge${tab.title}"
                setTextColor(if (isActive) Color.parseColor("#50FA7B") else Color.parseColor("#F8F8F2"))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = TextView(context).apply {
                text = if (tab.isPinned) "🔒" else "✕"
                setTextColor(if (tab.isPinned) Color.parseColor("#6272A4") else Color.parseColor("#FF5555"))
                textSize = 16f
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                if (!tab.isPinned) {
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sm.closeSession(index)
                        dismiss()
                    }
                }
            }

            headerRow.addView(titleView)
            headerRow.addView(closeBtn)
            card.addView(headerRow)

            // CWD Row
            val cwdTv = TextView(context).apply {
                text = "📂 ${tab.workingDir}"
                setTextColor(Color.parseColor("#8BE9FD"))
                textSize = 12f
                setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
            }
            card.addView(cwdTv)

            // Status & Quick Action Row (Pin & Rename)
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            val statusTv = TextView(context).apply {
                val alive = tab.processSession.isAlive()
                text = if (alive) "● Running" else "○ Idle"
                setTextColor(if (alive) Color.parseColor("#50FA7B") else Color.parseColor("#6272A4"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pinBtn = TextView(context).apply {
                text = if (tab.isPinned) "Unpin" else "Pin"
                setTextColor(Color.parseColor("#F1FA8C"))
                textSize = 12f
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#282A36"))
                    cornerRadius = 4f * density
                }
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sm.togglePinSession(index)
                    dismiss()
                }
            }

            val renameBtn = TextView(context).apply {
                text = "Rename"
                setTextColor(Color.parseColor("#8BE9FD"))
                textSize = 12f
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#282A36"))
                    cornerRadius = 4f * density
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (8 * density).toInt()
                }
                layoutParams = params
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val ctx = context ?: return@setOnClickListener
                    val input = android.widget.EditText(ctx).apply {
                        setText(tab.title)
                        selectAll()
                    }
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Rename Tab")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val newName = input.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                sm.renameSession(index, newName)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    dismiss()
                }
            }

            actionRow.addView(statusTv)
            actionRow.addView(pinBtn)
            actionRow.addView(renameBtn)
            card.addView(actionRow)

            listLayout.addView(card)
        }

        scroll.addView(listLayout)
        root.addView(scroll)
        return root
    }

    companion object {
        const val TAG = "TabsCardBottomSheet"
    }
}
