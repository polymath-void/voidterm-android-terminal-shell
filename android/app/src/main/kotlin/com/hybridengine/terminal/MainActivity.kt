package com.hybridengine.terminal

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var terminalSurface: TerminalSurfaceView
    private var terminalService: TerminalService? = null
    private var isBound = false

    // Tab Bar Views
    private lateinit var layoutTabStrip: LinearLayout
    private lateinit var btnAddTab: TextView

    // Text Selection Toolbar Views
    private var cardSelectionBar: View? = null

    // Agent HUD Views
    private lateinit var cardAgentHud: MaterialCardView
    private lateinit var textHudBadge: TextView
    private lateinit var textHudAgentName: TextView
    private lateinit var textHudThought: TextView
    private lateinit var textHudFile: TextView
    private lateinit var progressHud: ProgressBar
    private lateinit var btnHudDismiss: ImageView
    private var fabVoice: com.google.android.material.floatingactionbutton.FloatingActionButton? = null
    private lateinit var voiceManager: VoiceManager
    private val hudDismissHandler = Handler(Looper.getMainLooper())
    private var hudDismissRunnable: Runnable? = null

    // Modifier buttons cache for dynamic styling
    private var btnCtrl: TextView? = null
    private var btnAlt: TextView? = null
    private var btnShift: TextView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TerminalService.TerminalBinder
            val service = localBinder?.service
            terminalService = service
            isBound = true

            val sm = service?.sessionManager
            if (sm != null) {
                // Connect SessionManager events
                sm.onSessionOutput = { tab, output ->
                    runOnUiThread {
                        if (tab == sm.getActiveSession()) {
                            terminalSurface.appendOutput(output)
                            val cwd = sm.updateActiveWorkingDir()
                            val home = service.voidFS.homeDir.absolutePath
                            terminalSurface.updatePrompt(cwd, home)
                        }
                    }
                }

                sm.onActiveSessionChanged = { activeTab ->
                    runOnUiThread {
                        terminalSurface.setTerminalLines(activeTab.lines)
                        terminalSurface.setInputState(activeTab.currentInput, activeTab.cursorPosition)
                        terminalSurface.setScrollOffset(activeTab.scrollOffset)
                        val home = service.voidFS.homeDir.absolutePath
                        terminalSurface.updatePrompt(activeTab.workingDir, home)
                        renderTabs(sm)
                    }
                }

                sm.onSessionsListChanged = {
                    runOnUiThread {
                        renderTabs(sm)
                    }
                }

                // Initial render of tabs and active session state
                val active = sm.getActiveSession()
                if (active != null) {
                    terminalSurface.setTerminalLines(active.lines)
                    val home = service.voidFS.homeDir.absolutePath
                    terminalSurface.updatePrompt(active.workingDir, home)
                }
                renderTabs(sm)
            }

            // Connect broker HUD telemetry directly to the agent HUD overlay
            service?.broker?.hudTelemetryListener = { telemetry ->
                runOnUiThread {
                    updateAgentHud(telemetry)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Request Runtime Permissions (Storage, Notifications & Microphone)
        requestRequiredPermissions()

        // 2. Initialize Views
        terminalSurface = findViewById(R.id.terminal_surface)
        layoutTabStrip = findViewById(R.id.layout_tab_strip)
        btnAddTab = findViewById(R.id.btn_add_tab)

        cardSelectionBar = findViewById(R.id.card_selection_bar)
        val btnSelectionCopy = findViewById<View>(R.id.btn_selection_copy)
        val btnSelectionSelectAll = findViewById<View>(R.id.btn_selection_select_all)
        val btnSelectionClose = findViewById<View>(R.id.btn_selection_close)

        terminalSurface.onSelectionChanged = { isSelecting, selectedText ->
            runOnUiThread {
                if (isSelecting && !selectedText.isNullOrEmpty()) {
                    cardSelectionBar?.visibility = View.VISIBLE
                } else {
                    cardSelectionBar?.visibility = View.GONE
                }
            }
        }

        btnSelectionCopy?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val selected = terminalSurface.getSelectedText()
            val textToCopy = if (!selected.isNullOrEmpty()) {
                selected
            } else {
                terminalSurface.copyAllText()
            }
            if (!textToCopy.isNullOrEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("VoidTerm", textToCopy)
                clipboard.setPrimaryClip(clip)
                val msg = if (!selected.isNullOrEmpty()) "Selection copied" else "Terminal output copied"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            terminalSurface.clearSelection()
            cardSelectionBar?.visibility = View.GONE
        }

        btnSelectionSelectAll?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            terminalSurface.selectAllText()
        }

        btnSelectionClose?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            terminalSurface.clearSelection()
            cardSelectionBar?.visibility = View.GONE
        }

        cardAgentHud = findViewById(R.id.card_agent_hud)
        textHudBadge = findViewById(R.id.text_hud_badge)
        textHudAgentName = findViewById(R.id.text_hud_agent_name)
        textHudThought = findViewById(R.id.text_hud_thought)
        textHudFile = findViewById(R.id.text_hud_file)
        progressHud = findViewById(R.id.progress_hud)
        btnHudDismiss = findViewById(R.id.btn_hud_dismiss)
        // Mic button removed from dashboard, keeping permission operable for background pkgs/extensions
        fabVoice = null

        btnHudDismiss.setOnClickListener {
            cardAgentHud.visibility = View.GONE
        }

        btnAddTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            terminalService?.sessionManager?.createSession()
        }

        findViewById<View>(R.id.btn_extensions_hub)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            com.hybridengine.terminal.sheets.ExtensionsHubBottomSheet().show(
                supportFragmentManager,
                com.hybridengine.terminal.sheets.ExtensionsHubBottomSheet.TAG
            )
        }

        // Initialize and register installed polyglot extensions
        com.hybridengine.terminal.extensions.ExtensionManager.initExtensions(this)

        // ⊞ (Grid Icon) - Open Tab Card Bottom Sheet / Overview
        btnAddTab.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            terminalService?.sessionManager?.let { sm ->
                TabsCardBottomSheet().show(supportFragmentManager, sm)
            }
            true
        }

        setupKeyboardToolbar()

        // Wire FloatingActionButton to open SettingsActivity
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Initialize Live Voice Interaction with Agent
        voiceManager = VoiceManager(this).apply {
            onListeningStateChanged = { listening ->
                runOnUiThread {
                    if (listening) {
                        fabVoice?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5555"))
                        updateAgentHud(
                            HudTelemetry(
                                agent = "Voice Agent",
                                status = "listening",
                                thought = "🎙️ Listening to live voice command..."
                            )
                        )
                    } else {
                        fabVoice?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6272A4"))
                    }
                }
            }

            onSpeechPartial = { partial ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Live Voice Input",
                            status = "listening",
                            thought = "“$partial”"
                        )
                    )
                }
            }

            onSpeechResult = { recognizedText ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Voice Agent",
                            status = "executing",
                            thought = "Spoken Directive: \"$recognizedText\""
                        )
                    )
                    terminalService?.executeCommand(recognizedText)
                }
            }

            onSpeechError = { errorMsg ->
                runOnUiThread {
                    updateAgentHud(
                        HudTelemetry(
                            agent = "Voice Agent",
                            status = "error",
                            thought = errorMsg
                        )
                    )
                }
            }
        }

        fabVoice?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            } else {
                voiceManager.toggleListening()
            }
        }

        // 3. Start & Bind to Persistent TerminalService
        TerminalService.start(this)
        val serviceIntent = Intent(this, TerminalService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 4. Connect Native Inline Terminal Input directly to the active session
        terminalSurface.onCommandSubmitted = { command ->
            val trimmed = command.trim()
            if (trimmed.isNotEmpty()) {
                val sm = terminalService?.sessionManager
                val activeTab = sm?.getActiveSession()
                if (activeTab != null) {
                    if (trimmed.startsWith("/")) {
                        if (trimmed == "/hub" || trimmed == "/ext") {
                            runOnUiThread {
                                com.hybridengine.terminal.sheets.ExtensionsHubBottomSheet().show(
                                    supportFragmentManager,
                                    com.hybridengine.terminal.sheets.ExtensionsHubBottomSheet.TAG
                                )
                            }
                            return@onCommandSubmitted
                        }
                        val slashHandled = com.hybridengine.terminal.extensions.ExtensionManager.executeSlashCommand(
                            this@MainActivity,
                            activeTab,
                            trimmed
                        )
                        if (slashHandled) return@onCommandSubmitted
                    }
                    activeTab.processSession.write(trimmed)
                    // Update working directory and prompt after command execution
                    terminalSurface.postDelayed({
                        val cwd = sm.updateActiveWorkingDir()
                        val home = sm.voidFS.homeDir.absolutePath
                        terminalSurface.updatePrompt(cwd, home)
                    }, 100)
                } else {
                    terminalService?.executeCommand(trimmed)
                }
            }
        }

        // 5. Connect Dynamic Config and Resource Reloader to Terminal UI
        DynamicCoreLoader.onConfigReloaded = {
            runOnUiThread {
                if (::terminalSurface.isInitialized) {
                    terminalSurface.reloadConfig()
                }
            }
        }
    }

    private fun renderTabs(sm: SessionManager) {
        layoutTabStrip.removeAllViews()
        val density = resources.displayMetrics.density
        val activeIndex = sm.activeIndex

        for ((index, tab) in sm.sessions.withIndex()) {
            val isActive = index == activeIndex

            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = false

                val padH = (10 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(padH, padV, padH, padV)

                val bgColor = if (isActive) Color.parseColor("#282A36") else Color.parseColor("#181825")
                val strokeColor = if (isActive) Color.parseColor("#50FA7B") else Color.parseColor("#44475A")
                val strokeWidth = (if (isActive) 1.5f * density else 1f * density).toInt()

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(bgColor)
                    setStroke(strokeWidth, strokeColor)
                }

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (30 * density).toInt()
                ).apply {
                    setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                }
                layoutParams = params

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // Save current buffer & offset of leaving tab before switching
                    sm.getActiveSession()?.let { cur ->
                        cur.currentInput = terminalSurface.currentInputBuffer
                        cur.cursorPosition = terminalSurface.cursorPosition
                        cur.scrollOffset = terminalSurface.getScrollOffset()
                    }
                    sm.switchToSession(index)
                }

                // Long press on tab: Pin/Unpin or Rename dialog
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val options = arrayOf(
                        if (tab.isPinned) "Unpin Tab" else "Pin Tab",
                        "Rename Tab"
                    )
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(tab.title)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> sm.togglePinSession(index)
                                1 -> {
                                    val input = android.widget.EditText(this@MainActivity).apply {
                                        setText(tab.title)
                                        selectAll()
                                    }
                                    android.app.AlertDialog.Builder(this@MainActivity)
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
                                }
                            }
                        }
                        .show()
                    true
                }
            }

            // Tab Title Label (with pin indicator)
            val textTitle = TextView(this).apply {
                val pinBadge = if (tab.isPinned) "📌 " else ""
                text = "$pinBadge${tab.title}"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(if (isActive) Color.parseColor("#50FA7B") else Color.parseColor("#F8F8F2"))
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
            }
            tabLayout.addView(textTitle)

            // Close Tab Button (✕) if more than 1 tab and not pinned
            if (sm.sessions.size > 1 && !tab.isPinned) {
                val btnClose = TextView(this).apply {
                    text = "  ✕"
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#FF5555"))
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = false
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sm.closeSession(index)
                    }
                }
                tabLayout.addView(btnClose)
            }

            layoutTabStrip.addView(tabLayout)
        }
    }

    private fun setupKeyboardToolbar() {
        val row1Layout = findViewById<LinearLayout>(R.id.layout_keyboard_keys_row1) ?: return
        val row2Layout = findViewById<LinearLayout>(R.id.layout_keyboard_keys_row2) ?: return
        row1Layout.removeAllViews()
        row2Layout.removeAllViews()

        data class KeyDef(
            val label: String,
            val action: () -> Unit,
            val isActionKey: Boolean = false,
            val isAccentKey: Boolean = false,
            val keyType: String = "normal"
        )

        // Row 1 Base (8 keys): System, Modifiers, and UP arrow on the right
        val row1Base = listOf(
            KeyDef("ESC", { terminalSurface.insertText("\u001b") }, isActionKey = true),
            KeyDef("TAB", { terminalSurface.handleTabCompletion() }, isAccentKey = true),
            KeyDef("CTRL", {
                terminalSurface.isCtrlActive = !terminalSurface.isCtrlActive
                updateModifierButtonState(btnCtrl, terminalSurface.isCtrlActive, "#FF5555")
            }, keyType = "ctrl"),
            KeyDef("ALT", {
                terminalSurface.isAltActive = !terminalSurface.isAltActive
                updateModifierButtonState(btnAlt, terminalSurface.isAltActive, "#F1FA8C")
            }, keyType = "alt"),
            KeyDef("BKSP", { terminalSurface.deleteLastChar() }),
            KeyDef("HOME", { terminalSurface.setCursorToStart() }, isAccentKey = true),
            KeyDef("▲", { terminalSurface.handleHistoryUp() }, isActionKey = true, keyType = "arrow"),
            KeyDef("END", { terminalSurface.setCursorToEnd() }, isAccentKey = true)
        )

        val row1Extended = listOf(
            KeyDef("SHIFT", {
                terminalSurface.isShiftActive = !terminalSurface.isShiftActive
                updateModifierButtonState(btnShift, terminalSurface.isShiftActive, "#BD93F9")
            }, keyType = "shift"),
            KeyDef("COPY", { copySelectionToClipboard() }, isAccentKey = true),
            KeyDef("CLR", { terminalSurface.clearScreen() }, isActionKey = true),
            KeyDef("F1", { terminalSurface.insertText("\u001bOP") }),
            KeyDef("F2", { terminalSurface.insertText("\u001bOQ") }),
            KeyDef("F3", { terminalSurface.insertText("\u001bOR") }),
            KeyDef("F4", { terminalSurface.insertText("\u001bOS") }),
            KeyDef("F5", { terminalSurface.insertText("\u001b[15~") }),
            KeyDef("F12", { terminalSurface.insertText("\u001b[24~") })
        )

        // Row 2 Base (8 keys): Symbols, Paste, and Inverted-T [◀, ▼, ▶] on the right directly below ▲
        val row2Base = listOf(
            KeyDef("~", { terminalSurface.insertText("~") }),
            KeyDef("/", { terminalSurface.insertText("/") }),
            KeyDef("-", { terminalSurface.insertText("-") }),
            KeyDef("|", { terminalSurface.insertText("|") }),
            KeyDef("PASTE", { pasteFromClipboard() }, isAccentKey = true),
            KeyDef("◀", { terminalSurface.moveCursorLeft() }, keyType = "arrow"),
            KeyDef("▼", { terminalSurface.handleHistoryDown() }, isActionKey = true, keyType = "arrow"),
            KeyDef("▶", { terminalSurface.moveCursorRight() }, keyType = "arrow")
        )

        val row2Extended = listOf(
            KeyDef("$", { terminalSurface.insertText("$") }),
            KeyDef("\"", { terminalSurface.insertText("\"") }),
            KeyDef("'", { terminalSurface.insertText("'") }),
            KeyDef(":", { terminalSurface.insertText(":") }),
            KeyDef(";", { terminalSurface.insertText(";") }),
            KeyDef("&", { terminalSurface.insertText("&") }),
            KeyDef("?", { terminalSurface.insertText("?") }),
            KeyDef("_", { terminalSurface.insertText("_") }),
            KeyDef("=", { terminalSurface.insertText("=") }),
            KeyDef("\\", { terminalSurface.insertText("\\") }),
            KeyDef("(", { terminalSurface.insertText("(") }),
            KeyDef(")", { terminalSurface.insertText(")") })
        )

        val density = resources.displayMetrics.density
        val minWidthPx = (36 * density).toInt()
        val heightPx = (30 * density).toInt()
        val marginPx = (2 * density).toInt()
        val paddingHorizontalPx = (6 * density).toInt()

        val config = TerminalConfig(this)
        val row1Limit = config.row1Limit
        val row2Limit = config.row2Limit
        val row1Unlocked = config.row1Unlocked
        val row2Unlocked = config.row2Unlocked

        val availableWidth = (resources.displayMetrics.widthPixels - (16 * density).toInt())
        val row1EffectiveWidth = if (row1Unlocked) minWidthPx else kotlin.math.max(minWidthPx, (availableWidth - (marginPx * 2 * row1Limit)) / kotlin.math.max(1, row1Limit))
        val row2EffectiveWidth = if (row2Unlocked) minWidthPx else kotlin.math.max(minWidthPx, (availableWidth - (marginPx * 2 * row2Limit)) / kotlin.math.max(1, row2Limit))

        fun createKeyButton(key: KeyDef, isRow1: Boolean): TextView {
            val isUnlocked = if (isRow1) row1Unlocked else row2Unlocked
            val effectiveWidth = if (isRow1) row1EffectiveWidth else row2EffectiveWidth

            val btn = TextView(this).apply {
                text = key.label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                minWidth = if (isUnlocked) minWidthPx else effectiveWidth
                minimumWidth = if (isUnlocked) minWidthPx else effectiveWidth
                setPadding(paddingHorizontalPx, 0, paddingHorizontalPx, 0)
                isClickable = true
                isFocusable = false

                val textColor = when {
                    key.isActionKey -> Color.parseColor("#FF5555")
                    key.isAccentKey -> Color.parseColor("#50FA7B")
                    else -> Color.parseColor("#F8F8F2")
                }
                setTextColor(textColor)

                val bgColor = if (key.isActionKey) Color.parseColor("#282A36") else Color.parseColor("#21222C")
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 5 * density
                    setColor(bgColor)
                    setStroke((1 * density).toInt(), Color.parseColor("#44475A"))
                }

                val keyWidth = if (isUnlocked) minWidthPx else effectiveWidth
                layoutParams = LinearLayout.LayoutParams(keyWidth, heightPx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    key.action()
                }
            }

            when (key.keyType) {
                "ctrl" -> btnCtrl = btn
                "alt" -> btnAlt = btn
                "shift" -> btnShift = btn
            }

            return btn
        }

        // Apply row1 keys
        val finalRow1Keys = if (row1Unlocked) row1Base + row1Extended else row1Base.take(row1Limit)
        finalRow1Keys.forEach { key ->
            row1Layout.addView(createKeyButton(key, isRow1 = true))
        }

        // Apply row2 keys
        val finalRow2Keys = if (row2Unlocked) row2Base + row2Extended else row2Base.take(row2Limit)
        finalRow2Keys.forEach { key ->
            row2Layout.addView(createKeyButton(key, isRow1 = false))
        }

        val scrollRow1 = findViewById<android.widget.HorizontalScrollView>(R.id.keyboard_toolbar_row1)
        val scrollRow2 = findViewById<android.widget.HorizontalScrollView>(R.id.keyboard_toolbar_row2)
        scrollRow1?.isFillViewport = !row1Unlocked
        scrollRow2?.isFillViewport = !row2Unlocked
    }

    private fun copySelectionToClipboard() {
        val selected = terminalSurface.getSelectedText()
        val textToCopy = if (!selected.isNullOrEmpty()) {
            terminalSurface.clearSelection()
            cardSelectionBar?.visibility = View.GONE
            selected
        } else {
            terminalSurface.copyAllText()
        }
        if (textToCopy.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("VoidTerm", textToCopy)
            clipboard.setPrimaryClip(clip)
            val msg = if (!selected.isNullOrEmpty()) "Selection copied" else "Terminal output copied"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString()
            if (!text.isNullOrEmpty()) {
                terminalSurface.insertText(text)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isCtrl = event.isCtrlPressed || terminalSurface.isCtrlActive
            val isShift = event.isShiftPressed || terminalSurface.isShiftActive
            val sm = terminalService?.sessionManager

            if (isCtrl) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_C -> {
                        if (isShift) {
                            copySelectionToClipboard()
                        } else {
                            terminalSurface.handleCtrlC()
                            sm?.getActiveSession()?.processSession?.sendSignalSigInt()
                        }
                        terminalSurface.isCtrlActive = false
                        terminalSurface.isShiftActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_V -> {
                        pasteFromClipboard()
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_T -> {
                        sm?.createSession()
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_PAGE_UP -> {
                        sm?.let {
                            if (it.sessions.size > 1) {
                                val prev = (it.activeIndex - 1 + it.sessions.size) % it.sessions.size
                                it.switchToSession(prev)
                            }
                        }
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        sm?.let {
                            if (it.sessions.size > 1) {
                                val next = (it.activeIndex + 1) % it.sessions.size
                                it.switchToSession(next)
                            }
                        }
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_D -> {
                        val active = sm?.getActiveSession()
                        if (terminalSurface.currentInputBuffer.isEmpty()) {
                            active?.processSession?.write("\u0004")
                        } else {
                            terminalSurface.deleteCharUnderCursor()
                        }
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_L -> {
                        terminalSurface.clearScreen()
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                    KeyEvent.KEYCODE_Z -> {
                        sm?.getActiveSession()?.processSession?.write("\u001a")
                        terminalSurface.isCtrlActive = false
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateModifierButtonState(button: TextView?, isActive: Boolean, activeColorHex: String) {
        if (button == null) return
        val density = resources.displayMetrics.density
        if (isActive) {
            button.setTextColor(Color.parseColor("#282A36"))
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5 * density
                setColor(Color.parseColor(activeColorHex))
                setStroke((1 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
        } else {
            button.setTextColor(Color.parseColor("#F8F8F2"))
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5 * density
                setColor(Color.parseColor("#21222C"))
                setStroke((1 * density).toInt(), Color.parseColor("#44475A"))
            }
        }
    }

    private fun updateAgentHud(telemetry: HudTelemetry) {
        hudDismissRunnable?.let { hudDismissHandler.removeCallbacks(it) }

        cardAgentHud.alpha = 1f
        cardAgentHud.visibility = View.VISIBLE
        textHudAgentName.text = telemetry.agent
        textHudThought.text = telemetry.thought

        when (telemetry.status.lowercase()) {
            "working", "executing", "running" -> {
                textHudBadge.text = "● RUNNING"
                textHudBadge.setTextColor(Color.parseColor("#50FA7B"))
            }
            "thinking", "reasoning" -> {
                textHudBadge.text = "● THINKING"
                textHudBadge.setTextColor(Color.parseColor("#FFB86C"))
            }
            "completed", "success" -> {
                textHudBadge.text = "✔ COMPLETED"
                textHudBadge.setTextColor(Color.parseColor("#8BE9FD"))
            }
            "error", "failed" -> {
                textHudBadge.text = "✖ ERROR"
                textHudBadge.setTextColor(Color.parseColor("#FF5555"))
            }
            else -> {
                textHudBadge.text = "● ${telemetry.status.uppercase()}"
                textHudBadge.setTextColor(Color.parseColor("#BD93F9"))
            }
        }

        if (!telemetry.activeFile.isNullOrEmpty()) {
            textHudFile.text = "📄 ${telemetry.activeFile}"
            textHudFile.visibility = View.VISIBLE
        } else {
            textHudFile.visibility = View.GONE
        }

        if (telemetry.progress != null) {
            progressHud.visibility = View.VISIBLE
            progressHud.isIndeterminate = false
            progressHud.progress = (telemetry.progress * 100).toInt().coerceIn(0, 100)
        } else if (telemetry.status.lowercase() in listOf("working", "thinking", "running", "executing")) {
            progressHud.visibility = View.VISIBLE
            progressHud.isIndeterminate = true
        } else {
            progressHud.visibility = View.GONE
        }

        // Auto-dismiss completed or idle notifications after 4 seconds
        if (telemetry.status.lowercase() in listOf("completed", "idle", "success")) {
            val runnable = Runnable {
                cardAgentHud.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        cardAgentHud.visibility = View.GONE
                        cardAgentHud.alpha = 1f
                    }
                    .start()
            }
            hudDismissRunnable = runnable
            hudDismissHandler.postDelayed(runnable, 4000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::terminalSurface.isInitialized) {
            terminalSurface.reloadConfig()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hudDismissRunnable?.let { hudDismissHandler.removeCallbacks(it) }
        try {
            voiceManager.destroy()
        } catch (_: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestRequiredPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        Log.w("VoidTerm", "Could not launch manage storage intent: ${ex.message}")
                    }
                }
            }
        }
    }
}
