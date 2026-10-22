package com.hybridengine.terminal

import android.view.KeyEvent

/**
 * TerminalKeyEncoder: Translates Android KeyEvents and touch toolbar actions
 * directly into raw VT100 / XTerm byte escape sequences for the POSIX PTY.
 */
object TerminalKeyEncoder {

    fun encodeKeyEvent(
        keyCode: Int,
        event: KeyEvent,
        isCtrl: Boolean = false,
        isAlt: Boolean = false,
        isShift: Boolean = false,
        cursorKeysApp: Boolean = false
    ): ByteArray? {
        val ctrlPressed = isCtrl || event.isCtrlPressed
        val altPressed = isAlt || event.isAltPressed
        val shiftPressed = isShift || event.isShiftPressed

        // 1. Special Navigation & Control Keys
        val sequence: String? = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_DPAD_UP -> if (cursorKeysApp) "\u001bOA" else "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cursorKeysApp) "\u001bOB" else "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursorKeysApp) "\u001bOC" else "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cursorKeysApp) "\u001bOD" else "\u001b[D"
            KeyEvent.KEYCODE_TAB -> if (shiftPressed) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            KeyEvent.KEYCODE_F1 -> "\u001bOP"
            KeyEvent.KEYCODE_F2 -> "\u001bOQ"
            KeyEvent.KEYCODE_F3 -> "\u001bOR"
            KeyEvent.KEYCODE_F4 -> "\u001bOS"
            KeyEvent.KEYCODE_F5 -> "\u001b[15~"
            KeyEvent.KEYCODE_F6 -> "\u001b[17~"
            KeyEvent.KEYCODE_F7 -> "\u001b[18~"
            KeyEvent.KEYCODE_F8 -> "\u001b[19~"
            KeyEvent.KEYCODE_F9 -> "\u001b[20~"
            KeyEvent.KEYCODE_F10 -> "\u001b[21~"
            KeyEvent.KEYCODE_F11 -> "\u001b[23~"
            KeyEvent.KEYCODE_F12 -> "\u001b[24~"
            else -> null
        }

        if (sequence != null) {
            return if (altPressed && !sequence.startsWith("\u001b")) {
                ("\u001b$sequence").toByteArray(Charsets.UTF_8)
            } else {
                sequence.toByteArray(Charsets.UTF_8)
            }
        }

        // 2. Character Generation with Modifiers
        var unicodeChar = event.getUnicodeChar(event.metaState)
        if (unicodeChar == 0 && event.action == KeyEvent.ACTION_DOWN) {
            unicodeChar = event.keyCharacterMap.get(keyCode, event.metaState)
        }

        if (unicodeChar != 0) {
            val ch = unicodeChar.toChar()

            if (ctrlPressed) {
                val ctrlByte = encodeCtrlChar(ch)
                if (ctrlByte != null) {
                    return if (altPressed) byteArrayOf(0x1B, ctrlByte) else byteArrayOf(ctrlByte)
                }
            }

            if (altPressed) {
                return "\u001b$ch".toByteArray(Charsets.UTF_8)
            }

            return ch.toString().toByteArray(Charsets.UTF_8)
        }

        return null
    }

    fun encodeCtrlChar(ch: Char): Byte? {
        val upper = ch.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> (upper.code - 64).toByte()
            upper in '@'..' ' -> (upper.code - 64).toByte()
            upper == ' ' -> 0.toByte()
            upper == '[' -> 27.toByte()
            upper == '\\' -> 28.toByte()
            upper == ']' -> 29.toByte()
            upper == '^' -> 30.toByte()
            upper == '_' -> 31.toByte()
            upper == '?' -> 127.toByte()
            else -> null
        }
    }
}
