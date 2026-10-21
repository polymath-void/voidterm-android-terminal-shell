package com.hybridengine.terminal.keymap

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.Color
import androidx.core.content.ContextCompat

/**
 * KeyCatalog: Universal Key Catalog for VoidTerm.
 * Centralizes all physical and software keys (ESC, TAB, CTRL, ALT, SHIFT,
 * HOME, END, PGUP, PGDN, F1-F12, symbols, and actions).
 * Supports dynamic row budgeting and unlocked horizontal swipe scrolling.
 */
object KeyCatalog {

    private val TAG = "KeyCatalog"

    // ── Key Definition Data Class ──

    data class KeyDefinition(
        val keyCode: Int,              // Android KeyCode or custom
        val label: String,             // Display text/glyph
        val action: () -> Unit,        // Action lambda when pressed
        val isModifier: Boolean = false, // CTRL, ALT, SHIFT
        val isActionKey: Boolean = false, // ESC, CTRL, DEL, etc.
        val isAccentKey: Boolean = false, // TAB, Enter, etc.
        val keyType: String = "normal",  // "normal", "modifier", "action", "arrow"
        val rowWeight: Float = 1.0f    // For dynamic budgeting (even weight = 1.0)
    )

    // ── Modifier Keys ──

    val CTRL: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_CTRL_LEFT,
        label = "CTRL",
        action = {},
        isModifier = true,
        isActionKey = false,
        isAccentKey = false,
        keyType = "modifier",
        rowWeight = 1.0f
    )

    val ALT: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_ALT_LEFT,
        label = "ALT",
        action = {},
        isModifier = true,
        isActionKey = false,
        isAccentKey = false,
        keyType = "modifier",
        rowWeight = 1.0f
    )

    val SHIFT: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
        label = "SHIFT",
        action = {},
        isModifier = true,
        isActionKey = false,
        isAccentKey = false,
        keyType = "modifier",
        rowWeight = 1.0f
    )

    // ── Action Keys ──

    val ESC: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_ESCAPE,
        label = "ESC",
        action = { /* Insert escape sequence */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "action",
        rowWeight = 1.0f
    )

    val ENTER: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_ENTER,
        label = "ENT",
        action = { /* Send newline to terminal */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = true,
        keyType = "action",
        rowWeight = 1.0f
    )

    // ── Arrow Keys (Inverted-T Layout) ──

    // Upper row: ▲ (UP) - sits directly above ▼
    val ARROW_UP: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP,
        label = "▲",
        action = { /* Handle history up */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "arrow",
        rowWeight = 1.0f
    )

    // Lower row: inverted-T layout
    // ◀ (LEFT) sits to the left of ▼
    val ARROW_LEFT: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        label = "◀",
        action = { /* Move cursor left */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "arrow",
        rowWeight = 1.0f
    )

    // ▼ (DOWN) - sits directly below ▲, flanked by ◀ and ▶
    val ARROW_DOWN: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        label = "▼",
        action = { /* Handle history down */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "arrow",
        rowWeight = 1.0f
    )

    // ▶ (RIGHT) sits to the right of ▼
    val ARROW_RIGHT: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        label = "▶",
        action = { /* Move cursor right */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "arrow",
        rowWeight = 1.0f
    )

    // ── Tab Management Keys ──

    val TAB: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_TAB,
        label = "TAB",
        action = { /* Switch to next tab or insert tab */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = true,
        keyType = "action",
        rowWeight = 1.0f
    )

    val TAB_NEXT: KeyDefinition = TAB

    val TAB_PREV: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_TAB,
        label = "Shift+Tab",
        action = { /* Switch to previous tab */ },
        isModifier = true,
        isActionKey = true,
        isAccentKey = true,
        keyType = "action",
        rowWeight = 1.0f
    )

    // ── Function Keys (F1-F12) ──

    val F1: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_F1,
        label = "F1",
        action = { /* Execute F1 action */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "function",
        rowWeight = 1.0f
    )

    val F12: KeyDefinition = KeyDefinition(
        keyCode = android.view.KeyEvent.KEYCODE_F12,
        label = "F12",
        action = { /* Execute F12 action */ },
        isModifier = false,
        isActionKey = true,
        isAccentKey = false,
        keyType = "function",
        rowWeight = 1.0f
    )

    // ── Symbol Keys ──

    fun symbolKey(sym: String): KeyDefinition = KeyDefinition(
        keyCode = 0,
        label = sym,
        action = {},
        isModifier = false,
        isActionKey = false,
        isAccentKey = false,
        keyType = "symbol",
        rowWeight = 1.0f
    )

    val SLASH: KeyDefinition = symbolKey("/")
    val DASH: KeyDefinition = symbolKey("-")
    val UNDERSCORE: KeyDefinition = symbolKey("_")
    val TILDE: KeyDefinition = symbolKey("~")
    val PIPE: KeyDefinition = symbolKey("|")
    val DOLLAR: KeyDefinition = symbolKey("$")
    val QUOTE: KeyDefinition = symbolKey("\"")
    val SQUOTE: KeyDefinition = symbolKey("'")
    val COLON: KeyDefinition = symbolKey(":")
    val SEMICOLON: KeyDefinition = symbolKey(";")
    val AMPERSAND: KeyDefinition = symbolKey("&")
    val QUESTION: KeyDefinition = symbolKey("?")

    // ── Universal Key Catalog (All keys) ──

    /** Returns the complete list of key definitions in logical order. */
    fun getAllKeys(): List<KeyDefinition> = listOf(
        // Modifier row
        CTRL, ALT, SHIFT,

        // Action row
        ESC, TAB,

        // Arrow cluster (inverted-T)
        ARROW_UP,
        ARROW_LEFT, ARROW_DOWN, ARROW_RIGHT,

        // Tab keys
        TAB_NEXT, TAB_PREV,

        // Function keys
        F1, F12,

        // Navigation and editing
        BACKSPACE, DELETE, INSERT,
        HOME, END,

        // Symbol keys
        SLASH, DASH, UNDERSCORE, TILDE, PIPE, DOLLAR,
        QUOTE, SQUOTE, COLON, SEMICOLON, AMPERSAND, QUESTION
    )

    /** Returns only the physical arrow cluster keys (▲ ◀ ▼ ▶). */
    fun getArrowCluster(): List<KeyDefinition> = listOf(ARROW_UP, ARROW_LEFT, ARROW_DOWN, ARROW_RIGHT)

    /** Returns only the modifier keys (CTRL, ALT, SHIFT). */
    fun getModifiers(): List<KeyDefinition> = listOf(CTRL, ALT, SHIFT)
}