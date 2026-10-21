package com.hybridengine.terminal

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager

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
    GRUVBOX(
        themeName = "Gruvbox",
        background = Color.parseColor("#273746"),
        foreground = Color.parseColor("#d5c4a1"),
        promptColor = Color.parseColor("#b8bb26"),
        cursor = Color.parseColor("#fe8019"),
        ansiColors = intArrayOf(
            Color.parseColor("#273746"), // 0 Black
            Color.parseColor("#cc241d"), // 1 Red
            Color.parseColor("#98971a"), // 2 Green
            Color.parseColor("#d65d0e"), // 3 Yellow
            Color.parseColor("#458588"), // 4 Blue
            Color.parseColor("#b16286"), // 5 Magenta
            Color.parseColor("#d65d0e"), // 6 Cyan
            Color.parseColor("#fbf1c7")  // 7 White
        )
    ),
    TOKYO_NIGHT(
        themeName = "Tokyo Night",
        background = Color.parseColor("#1a1b26"),
        foreground = Color.parseColor("#c0cae9"),
        promptColor = Color.parseColor("#7aa2f7"),
        cursor = Color.parseColor("#7dcfff"),
        ansiColors = intArrayOf(
            Color.parseColor("#1a1b26"), // 0 Black
            Color.parseColor("#f7768e"), // 1 Red
            Color.parseColor("#9ece6a"), // 2 Green
            Color.parseColor("#7dcfff"), // 3 Yellow
            Color.parseColor("#7aa2f7"), // 4 Blue
            Color.parseColor("#bb9af7"), // 5 Magenta
            Color.parseColor("#7dcfff"), // 6 Cyan
            Color.parseColor("#c0cae9")  // 7 White
        )
    ),
    CYBERPUNK(
        themeName = "Cyberpunk",
        background = Color.parseColor("#0a0a1e"),
        foreground = Color.parseColor("#ffff00"),
        promptColor = Color.parseColor("#ff00ff"),
        cursor = Color.parseColor("#00ffff"),
        ansiColors = intArrayOf(
            Color.parseColor("#0a0a1e"), // 0 Black
            Color.parseColor("#ff0049"), // 1 Red
            Color.parseColor("#ff9e00"), // 2 Green
            Color.parseColor("#ff00ff"), // 3 Yellow
            Color.parseColor("#00ffff"), // 3 Blue
            Color.parseColor("#ff00ff"), // 4 Magenta
            Color.parseColor("#00ffff"), // 5 Cyan
            Color.parseColor("#ffff00")  // 6 White
        )
    ),
    MATERIAL_DARK(
        themeName = "Material Dark",
        background = Color.parseColor("#121212"),
        foreground = Color.parseColor("#EEEEEE"),
        promptColor = Color.parseColor("#81D4FA"),
        cursor = Color.parseColor("#CFD8DC"),
        ansiColors = intArrayOf(
            Color.parseColor("#121212"), // 0 Black
            Color.parseColor("#EF5350"), // 1 Red
            Color.parseColor("#81C784"), // 2 Green
            Color.parseColor("#FFB300"), // 3 Yellow
            Color.parseColor("#03A9F4"), // 4 Blue
            Color.parseColor("#BA68C8"), // 5 Magenta
            Color.parseColor("#03A9F4"), // 6 Cyan
            Color.parseColor("#FFFFFF")  // 7 White
        )
    ),
    SOLARIZED_DARK(
        themeName = "Solarized Dark",
        background = Color.parseColor("#002B36"),
        foreground = Color.parseColor("#839496"),
        promptColor = Color.parseColor("#2AA198"),
        cursor = Color.parseColor("#93A1A1"),
        ansiColors = intArrayOf(
            Color.parseColor("#073642"), // 0 Black
            Color.parseColor("#DC322F"), // 1 Red
            Color.parseColor("#859900"), // 2 Green
            Color.parseColor("#B58900"), // 3 Yellow
            Color.parseColor("#268BD2"), // 4 Blue
            Color.parseColor("#D33682"), // 5 Magenta
            Color.parseColor("#2AA198"), // 6 Cyan
            Color.parseColor("#EEE8D5")  // 7 White
        )
    ),
    ONE_DARK(
        themeName = "One Dark",
        background = Color.parseColor("#282C34"),
        foreground = Color.parseColor("#ABB2BF"),
        promptColor = Color.parseColor("#98C379"),
        cursor = Color.parseColor("#528BFF"),
        ansiColors = intArrayOf(
            Color.parseColor("#282C34"), // 0 Black
            Color.parseColor("#E06C75"), // 1 Red
            Color.parseColor("#98C379"), // 2 Green
            Color.parseColor("#E5C07B"), // 3 Yellow
            Color.parseColor("#61AFEF"), // 4 Blue
            Color.parseColor("#C678DD"), // 5 Magenta
            Color.parseColor("#56B6C2"), // 6 Cyan
            Color.parseColor("#ABB2BF")  // 7 White
        )
    ),
    MONOKAI_PRO(
        themeName = "Monokai Pro",
        background = Color.parseColor("#2D2A2E"),
        foreground = Color.parseColor("#FCFCFA"),
        promptColor = Color.parseColor("#A9DC76"),
        cursor = Color.parseColor("#FFD866"),
        ansiColors = intArrayOf(
            Color.parseColor("#221F22"), // 0 Black
            Color.parseColor("#FF6188"), // 1 Red
            Color.parseColor("#A9DC76"), // 2 Green
            Color.parseColor("#FFD866"), // 3 Yellow
            Color.parseColor("#78DCE8"), // 4 Blue
            Color.parseColor("#AB9DF2"), // 5 Magenta
            Color.parseColor("#78DCE8"), // 6 Cyan
            Color.parseColor("#FCFCFA")  // 7 White
        )
    ),
    MATRIX_GREEN(
        themeName = "Matrix Green",
        background = Color.parseColor("#0D1117"),
        foreground = Color.parseColor("#00FF66"),
        promptColor = Color.parseColor("#33FF33"),
        cursor = Color.parseColor("#00FF00"),
        ansiColors = intArrayOf(
            Color.parseColor("#000000"), // 0 Black
            Color.parseColor("#008800"), // 1 Red
            Color.parseColor("#00FF66"), // 2 Green
            Color.parseColor("#66FF66"), // 3 Yellow
            Color.parseColor("#00CC44"), // 4 Blue
            Color.parseColor("#00AA33"), // 5 Magenta
            Color.parseColor("#33FF99"), // 6 Cyan
            Color.parseColor("#CCFFCC")  // 7 White
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
 * Configuration manager backed by PreferenceManager.getDefaultSharedPreferences and voidterm.json.
 * Supports padding, font, cursor, theme, and all visual configuration properties.
 */
class TerminalConfig(private val context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var theme: TerminalTheme
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_THEME)) {
                return TerminalTheme.fromString(json.getString(KEY_THEME))
            }
            val raw = prefs.getString(KEY_THEME, null)
                ?: prefs.getString(LEGACY_KEY_THEME, TerminalTheme.DRACULA.name)
                ?: TerminalTheme.DRACULA.name
            return TerminalTheme.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
            // Also update voidterm.json
            updateVoidTermJson("theme", value.name)
        }

    var fontSize: Float
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_FONT_SIZE)) {
                return json.optDouble(KEY_FONT_SIZE, DEFAULT_FONT_SIZE.toDouble()).toFloat().coerceIn(20f, 100f)
            }
            val fromString = try {
                prefs.getString(KEY_FONT_SIZE, null)?.toFloatOrNull()
            } catch (_: Exception) {
                null
            }
            if (fromString != null) return fromString.coerceIn(20f, 100f)

            return try {
                prefs.getFloat(KEY_FONT_SIZE, prefs.getFloat(LEGACY_KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
            } catch (_: Exception) {
                DEFAULT_FONT_SIZE
            }.coerceIn(20f, 100f)
        }
        set(value) {
            prefs.edit()
                .putFloat(KEY_FONT_SIZE, value.coerceIn(20f, 100f))
                .putString(KEY_FONT_SIZE, value.coerceIn(20f, 100f).toInt().toString())
                .apply()
            // Also update voidterm.json
            updateVoidTermJson("font_size", value.toString())
        }

    var cursorStyle: CursorStyle
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_CURSOR_STYLE)) {
                return CursorStyle.fromString(json.getString(KEY_CURSOR_STYLE))
            }
            val raw = prefs.getString(KEY_CURSOR_STYLE, null)
                ?: prefs.getString(LEGACY_KEY_CURSOR_STYLE, CursorStyle.BLOCK.name)
                ?: CursorStyle.BLOCK.name
            return CursorStyle.fromString(raw)
        }
        set(value) {
            prefs.edit().putString(KEY_CURSOR_STYLE, value.name).apply()
            // Also update voidterm.json
            updateVoidTermJson("cursor_style", value.name)
        }

    var cursorBlink: Boolean
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_CURSOR_BLINK)) {
                return json.optBoolean(KEY_CURSOR_BLINK, true)
            }
            return if (prefs.contains(KEY_CURSOR_BLINK)) {
                prefs.getBoolean(KEY_CURSOR_BLINK, true)
            } else {
                prefs.getBoolean(LEGACY_KEY_CURSOR_BLINK, true)
            }
        }
        set(value) {
            prefs.edit().putBoolean(KEY_CURSOR_BLINK, value).apply()
            // Also update voidterm.json
            updateVoidTermJson("cursor_blink", value.toString())
        }

    // Padding configuration (from voidterm.json)
    var paddingTopDp: Int
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_PADDING_TOP)) {
                return json.getInt(KEY_PADDING_TOP)
            }
            val fromPrefs = prefs.getInt(KEY_PADDING_TOP, 8)
            return fromPrefs
        }
        set(value) {
            prefs.edit().putInt(KEY_PADDING_TOP, value).apply()
            // Also update voidterm.json
            updateVoidTermJson("padding_top_dp", value.toString())
        }

    var paddingBottomDp: Int
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_PADDING_BOTTOM)) {
                return json.getInt(KEY_PADDING_BOTTOM)
            }
            val fromPrefs = prefs.getInt(KEY_PADDING_BOTTOM, 8)
            return fromPrefs
        }
        set(value) {
            prefs.edit().putInt(KEY_PADDING_BOTTOM, value).apply()
            // Also update voidterm.json
            updateVoidTermJson("padding_bottom_dp", value.toString())
        }

    var paddingHorizontalDp: Int
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has(KEY_PADDING_HORIZONTAL)) {
                return json.getInt(KEY_PADDING_HORIZONTAL)
            }
            val fromPrefs = prefs.getInt(KEY_PADDING_HORIZONTAL, 12)
            return fromPrefs
        }
        set(value) {
            prefs.edit().putInt(KEY_PADDING_HORIZONTAL, value).apply()
            // Also update voidterm.json
            updateVoidTermJson("padding_horizontal_dp", value.toString())
        }

    var defaultWorkingDir: String
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("default_working_dir")) {
                val path = json.getString("default_working_dir")
                return resolvePath(path)
            }
            val fromPrefs = prefs.getString("default_working_dir", null)
            if (!fromPrefs.isNullOrBlank()) {
                return resolvePath(fromPrefs)
            }
            return java.io.File(context.filesDir, "home").absolutePath
        }
        set(value) {
            prefs.edit().putString("default_working_dir", value).apply()
            updateVoidTermJson("default_working_dir", value)
        }

    var cursorBlinkRateMs: Long
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("cursor_blink_rate_ms")) {
                return json.optLong("cursor_blink_rate_ms", 500L)
            }
            return prefs.getLong("cursor_blink_rate_ms", 500L)
        }
        set(value) {
            prefs.edit().putLong("cursor_blink_rate_ms", value).apply()
            updateVoidTermJson("cursor_blink_rate_ms", value.toString())
        }

    var fontFamily: String
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("font_family")) {
                return json.getString("font_family")
            }
            return prefs.getString("font_family", "JetBrainsMono") ?: "JetBrainsMono"
        }
        set(value) {
            prefs.edit().putString("font_family", value).apply()
            updateVoidTermJson("font_family", value)
        }

    var row1Limit: Int
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("row1_limit")) return json.optInt("row1_limit", 8)
            return prefs.getInt("row1_limit", 8)
        }
        set(value) {
            prefs.edit().putInt("row1_limit", value).apply()
            updateVoidTermJson("row1_limit", value)
        }

    var row2Limit: Int
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("row2_limit")) return json.optInt("row2_limit", 8)
            return prefs.getInt("row2_limit", 8)
        }
        set(value) {
            prefs.edit().putInt("row2_limit", value).apply()
            updateVoidTermJson("row2_limit", value)
        }

    var row1Unlocked: Boolean
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("row1_unlocked")) return json.optBoolean("row1_unlocked", false)
            return prefs.getBoolean("row1_unlocked", false)
        }
        set(value) {
            prefs.edit().putBoolean("row1_unlocked", value).apply()
            updateVoidTermJson("row1_unlocked", value)
        }

    var row2Unlocked: Boolean
        get() {
            val json = DynamicCoreLoader.loadConfig(context)
            if (json.has("row2_unlocked")) return json.optBoolean("row2_unlocked", false)
            return prefs.getBoolean("row2_unlocked", false)
        }
        set(value) {
            prefs.edit().putBoolean("row2_unlocked", value).apply()
            updateVoidTermJson("row2_unlocked", value)
        }

    private fun resolvePath(path: String): String {
        val home = java.io.File(context.filesDir, "home").absolutePath
        return path.replace("\$HOME", home).replace("~", home)
    }

    private fun updateVoidTermJson(key: String, value: Any) {
        try {
            val configFile = java.io.File(context.filesDir, "usr/etc/voidterm.json")
            val json = if (configFile.exists()) {
                try {
                    org.json.JSONObject(configFile.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                    org.json.JSONObject()
                }
            } else {
                org.json.JSONObject()
            }
            json.put(key, value)
            configFile.parentFile?.mkdirs()
            java.io.FileOutputStream(configFile).use {
                it.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_CURSOR_STYLE = "cursor_style"
        const val KEY_CURSOR_BLINK = "cursor_blink"

        const val KEY_PADDING_TOP = "padding_top_dp"
        const val KEY_PADDING_BOTTOM = "padding_bottom_dp"
        const val KEY_PADDING_HORIZONTAL = "padding_horizontal_dp"

        private const val LEGACY_KEY_THEME = "terminal_theme"
        private const val LEGACY_KEY_FONT_SIZE = "terminal_font_size"
        private const val LEGACY_KEY_CURSOR_STYLE = "terminal_cursor_style"
        private const val LEGACY_KEY_CURSOR_BLINK = "terminal_cursor_blink"

        const val DEFAULT_FONT_SIZE = 38f
    }
}
