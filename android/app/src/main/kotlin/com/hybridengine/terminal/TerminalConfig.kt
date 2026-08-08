package com.hybridengine.terminal

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Terminal theme definitions containing full color palettes.
 */
enum class TerminalTheme(
    val themeName: String,
    val background: Int,
    val foreground: Int,
    val promptColor: Int,
    val cursor: Int,
    val ansiColors: IntArray
) {
    DRACULA(
        themeName = "Dracula",
        background = Color.parseColor("#282A36"),
        foreground = Color.parseColor("#F8F8F2"),
        promptColor = Color.parseColor("#50FA7B"),
        cursor = Color.parseColor("#8BE9FD"),
        ansiColors = intArrayOf(
            Color.parseColor("#21222C"), // 0 Black
            Color.parseColor("#FF5555"), // 1 Red
            Color.parseColor("#50FA7B"), // 2 Green
            Color.parseColor("#F1FA8C"), // 3 Yellow
            Color.parseColor("#BD93F9"), // 4 Blue
            Color.parseColor("#FF79C6"), // 5 Magenta
            Color.parseColor("#8BE9FD"), // 6 Cyan
            Color.parseColor("#F8F8F2")  // 7 White
        )
    ),
    NORD(
        themeName = "Nord",
        background = Color.parseColor("#2E3440"),
        foreground = Color.parseColor("#ECEFF4"),
        promptColor = Color.parseColor("#A3BE8C"),
        cursor = Color.parseColor("#88C0D0"),
        ansiColors = intArrayOf(
            Color.parseColor("#3B4252"), // 0 Black
            Color.parseColor("#BF616A"), // 1 Red
            Color.parseColor("#A3BE8C"), // 2 Green
            Color.parseColor("#EBCB8B"), // 3 Yellow
            Color.parseColor("#81A1C1"), // 4 Blue
            Color.parseColor("#B48EAD"), // 5 Magenta
            Color.parseColor("#88C0D0"), // 6 Cyan
            Color.parseColor("#E5E9F0")  // 7 White
        )
    ),
    MONOKAI(
        themeName = "Monokai",
        background = Color.parseColor("#272822"),
        foreground = Color.parseColor("#F8F8F2"),
        promptColor = Color.parseColor("#A6E22E"),
        cursor = Color.parseColor("#F8F8F0"),
        ansiColors = intArrayOf(
            Color.parseColor("#272822"), // 0 Black
            Color.parseColor("#F92672"), // 1 Red
            Color.parseColor("#A6E22E"), // 2 Green
            Color.parseColor("#F4BF75"), // 3 Yellow
            Color.parseColor("#66D9EF"), // 4 Blue
            Color.parseColor("#AE81FF"), // 5 Magenta
            Color.parseColor("#A1EFE4"), // 6 Cyan
            Color.parseColor("#F8F8F2")  // 7 White
        )
    );

    companion object {
        fun fromString(name: String): TerminalTheme {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) || it.themeName.equals(name, ignoreCase = true) }
                ?: DRACULA
        }
    }
}

/**
 * Terminal cursor shape styles.
 */
enum class CursorStyle(val symbol: String) {
    BLOCK("█"),
    UNDERLINE("_"),
    BAR("|");

    companion object {
        fun fromString(name: String): CursorStyle {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: BLOCK
        }
    }
}

/**
 * Configuration manager backed by SharedPreferences.
 */
class TerminalConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var theme: TerminalTheme
        get() {
            val raw = prefs.getString(KEY_THEME, TerminalTheme.DRACULA.name) ?: TerminalTheme.DRACULA.name
            return TerminalTheme.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SIZE, value.coerceIn(20f, 100f)).apply()
        }

    var cursorStyle: CursorStyle
        get() {
            val raw = prefs.getString(KEY_CURSOR_STYLE, CursorStyle.BLOCK.name) ?: CursorStyle.BLOCK.name
            return CursorStyle.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_CURSOR_STYLE, value.name).apply()
        }

    var cursorBlink: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_BLINK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CURSOR_BLINK, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "voidterm_terminal_config"
        private const val KEY_THEME = "terminal_theme"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val KEY_CURSOR_STYLE = "terminal_cursor_style"
        private const val KEY_CURSOR_BLINK = "terminal_cursor_blink"
        const val DEFAULT_FONT_SIZE = 38f
    }
}
