package com.moonlight.material.stream.input

import android.view.KeyEvent

/**
 * Representation of a key ready to be sent to the GameStream host.
 *
 * Ported from Sources/MoonlightMac/Input/KeycodeMappings.swift.
 */
data class HostKey(
    val virtualKey: Short,
    val extended: Boolean,
    val flags: Byte = 0
) {
    // The GameStream tag accompanies a VK code: virtualKey | 0x8000
    val wireCode: Short
        get() = (virtualKey.toInt() or 0x8000).toShort()

    val extendedModifier: Byte
        get() = if (extended) KeycodeMappings.MODIFIER_EXTENDED else 0
}

/**
 * Android KeyEvent to Windows Virtual Key (VK) mapping.
 *
 * Ported directly from Sources/MoonlightMac/Input/KeycodeMappings.swift and KeyboardTranslator.java.
 */
object KeycodeMappings {
    // Windows Virtual Key codes
    const val VK_BACK: Short       = 0x08
    const val VK_TAB: Short        = 0x09
    const val VK_CLEAR: Short      = 0x0C
    const val VK_RETURN: Short     = 0x0D
    const val VK_PAUSE: Short      = 0x13
    const val VK_CAPITAL: Short    = 0x14 // Caps Lock
    const val VK_ESCAPE: Short     = 0x1B
    const val VK_SPACE: Short      = 0x20
    const val VK_PRIOR: Short      = 0x21 // Page Up
    const val VK_NEXT: Short       = 0x22 // Page Down
    const val VK_END: Short        = 0x23
    const val VK_HOME: Short       = 0x24
    const val VK_LEFT: Short       = 0x25
    const val VK_UP: Short         = 0x26
    const val VK_RIGHT: Short      = 0x27
    const val VK_DOWN: Short       = 0x28
    const val VK_SNAPSHOT: Short   = 0x2C // Print Screen
    const val VK_INSERT: Short     = 0x2D
    const val VK_DELETE: Short     = 0x2E
    const val VK_LWIN: Short       = 0x5B // Left Windows Key
    const val VK_RWIN: Short       = 0x5C // Right Windows Key
    const val VK_APPS: Short       = 0x5D // Context Menu

    // Left/right-specific modifier codes (matches moonlight-qt / moonlight-mac)
    const val VK_LSHIFT: Short     = 0xA0.toShort()
    const val VK_RSHIFT: Short     = 0xA1.toShort()
    const val VK_LCONTROL: Short   = 0xA2.toShort()
    const val VK_RCONTROL: Short   = 0xA3.toShort()
    const val VK_LMENU: Short      = 0xA4.toShort() // Left Alt
    const val VK_RMENU: Short      = 0xA5.toShort() // Right Alt

    // Numpad codes
    const val VK_NUMPAD0: Short    = 0x60
    const val VK_NUMPAD1: Short    = 0x61
    const val VK_NUMPAD2: Short    = 0x62
    const val VK_NUMPAD3: Short    = 0x63
    const val VK_NUMPAD4: Short    = 0x64
    const val VK_NUMPAD5: Short    = 0x65
    const val VK_NUMPAD6: Short    = 0x66
    const val VK_NUMPAD7: Short    = 0x67
    const val VK_NUMPAD8: Short    = 0x68
    const val VK_NUMPAD9: Short    = 0x69
    const val VK_MULTIPLY: Short   = 0x6A
    const val VK_ADD: Short        = 0x6B
    const val VK_SEPARATOR: Short  = 0x6C
    const val VK_SUBTRACT: Short   = 0x6D
    const val VK_DECIMAL: Short    = 0x6E
    const val VK_DIVIDE: Short     = 0x6F
    const val VK_NUMLOCK: Short    = 0x90.toShort()
    const val VK_SCROLL: Short     = 0x91.toShort()

    // Key actions
    const val KEY_ACTION_DOWN: Byte = 0x03
    const val KEY_ACTION_UP: Byte   = 0x04

    // Modifier flags
    const val MODIFIER_SHIFT: Byte    = 0x01
    const val MODIFIER_CTRL: Byte     = 0x02
    const val MODIFIER_ALT: Byte      = 0x04
    const val MODIFIER_META: Byte     = 0x08
    const val MODIFIER_EXTENDED: Byte = 0x10

    // Sunshine protocol extension flag
    const val SS_KBE_FLAG_NON_NORMALIZED: Byte = 0x01

    private val extendedVirtualKeys: Set<Short> = setOf(
        VK_PRIOR, VK_NEXT, VK_END, VK_HOME,
        VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN,
        VK_INSERT, VK_DELETE, VK_LWIN, VK_RWIN,
        VK_DIVIDE, VK_RCONTROL, VK_RMENU, VK_APPS
    )

    /**
     * Translates an Android keycode into a HostKey.
     */
    fun hostKey(androidKeyCode: Int, swapWinAltKeys: Boolean = false): HostKey? {
        val (vk, isExplicitExtended) = when (androidKeyCode) {
            // Letters A-Z (0x41 - 0x5A)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                Pair((0x41 + (androidKeyCode - KeyEvent.KEYCODE_A)).toShort(), false)
            }

            // Digits 0-9 (0x30 - 0x39)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                Pair((0x30 + (androidKeyCode - KeyEvent.KEYCODE_0)).toShort(), false)
            }

            // Function keys F1-F12 (0x70 - 0x7B)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                Pair((0x70 + (androidKeyCode - KeyEvent.KEYCODE_F1)).toShort(), false)
            }

            // Numpad 0-9
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                Pair((VK_NUMPAD0 + (androidKeyCode - KeyEvent.KEYCODE_NUMPAD_0)).toShort(), false)
            }

            // Enter distinction
            KeyEvent.KEYCODE_ENTER -> Pair(VK_RETURN, false)
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Pair(VK_RETURN, true)

            // Navigation & Editing
            KeyEvent.KEYCODE_DEL -> Pair(VK_BACK, false)
            KeyEvent.KEYCODE_FORWARD_DEL -> Pair(VK_DELETE, true)
            KeyEvent.KEYCODE_TAB -> Pair(VK_TAB, false)
            KeyEvent.KEYCODE_SPACE -> Pair(VK_SPACE, false)
            KeyEvent.KEYCODE_ESCAPE -> Pair(VK_ESCAPE, false)
            KeyEvent.KEYCODE_CAPS_LOCK -> Pair(VK_CAPITAL, false)
            KeyEvent.KEYCODE_CLEAR -> Pair(VK_CLEAR, false)
            KeyEvent.KEYCODE_SCROLL_LOCK -> Pair(VK_SCROLL, false)
            KeyEvent.KEYCODE_NUM_LOCK -> Pair(VK_NUMLOCK, false)
            KeyEvent.KEYCODE_PAGE_UP -> Pair(VK_PRIOR, true)
            KeyEvent.KEYCODE_PAGE_DOWN -> Pair(VK_NEXT, true)
            KeyEvent.KEYCODE_MOVE_HOME -> Pair(VK_HOME, true)
            KeyEvent.KEYCODE_MOVE_END -> Pair(VK_END, true)
            KeyEvent.KEYCODE_INSERT -> Pair(VK_INSERT, true)
            KeyEvent.KEYCODE_BREAK -> Pair(VK_PAUSE, false)
            KeyEvent.KEYCODE_SYSRQ -> Pair(VK_SNAPSHOT, false)

            // Arrows
            KeyEvent.KEYCODE_DPAD_LEFT -> Pair(VK_LEFT, true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Pair(VK_RIGHT, true)
            KeyEvent.KEYCODE_DPAD_UP -> Pair(VK_UP, true)
            KeyEvent.KEYCODE_DPAD_DOWN -> Pair(VK_DOWN, true)

            // Modifiers
            KeyEvent.KEYCODE_SHIFT_LEFT -> Pair(VK_LSHIFT, false)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Pair(VK_RSHIFT, false)
            KeyEvent.KEYCODE_CTRL_LEFT -> Pair(VK_LCONTROL, false)
            KeyEvent.KEYCODE_CTRL_RIGHT -> Pair(VK_RCONTROL, true)
            KeyEvent.KEYCODE_ALT_LEFT -> Pair(if (swapWinAltKeys) VK_LWIN else VK_LMENU, if (swapWinAltKeys) true else false)
            KeyEvent.KEYCODE_ALT_RIGHT -> Pair(if (swapWinAltKeys) VK_RWIN else VK_RMENU, true)
            KeyEvent.KEYCODE_META_LEFT -> Pair(if (swapWinAltKeys) VK_LMENU else VK_LWIN, true)
            KeyEvent.KEYCODE_META_RIGHT -> Pair(if (swapWinAltKeys) VK_RMENU else VK_RWIN, true)
            KeyEvent.KEYCODE_MENU -> Pair(VK_APPS, true)

            // Punctuation & Symbols
            KeyEvent.KEYCODE_MINUS -> Pair(0xBD.toShort(), false)
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> Pair(0xBB.toShort(), false)
            KeyEvent.KEYCODE_LEFT_BRACKET -> Pair(0xDB.toShort(), false)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Pair(0xDD.toShort(), false)
            KeyEvent.KEYCODE_BACKSLASH -> Pair(0xDC.toShort(), false)
            KeyEvent.KEYCODE_SEMICOLON -> Pair(0xBA.toShort(), false)
            KeyEvent.KEYCODE_APOSTROPHE -> Pair(0xDE.toShort(), false)
            KeyEvent.KEYCODE_GRAVE -> Pair(0xC0.toShort(), false)
            KeyEvent.KEYCODE_COMMA -> Pair(0xBC.toShort(), false)
            KeyEvent.KEYCODE_PERIOD -> Pair(0xBE.toShort(), false)
            KeyEvent.KEYCODE_SLASH -> Pair(0xBF.toShort(), false)

            // Numpad Math
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> Pair(VK_DIVIDE, true)
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> Pair(VK_MULTIPLY, false)
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Pair(VK_SUBTRACT, false)
            KeyEvent.KEYCODE_NUMPAD_ADD -> Pair(VK_ADD, false)
            KeyEvent.KEYCODE_NUMPAD_DOT -> Pair(VK_DECIMAL, false)

            else -> return null
        }

        val extended = isExplicitExtended || extendedVirtualKeys.contains(vk)
        return HostKey(virtualKey = vk, extended = extended)
    }

    /**
     * Extracts modifier flags from an Android KeyEvent.
     */
    fun extractModifiers(event: KeyEvent, extended: Boolean = false): Byte {
        var mod: Int = 0
        if (event.isShiftPressed) mod = mod or MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) mod = mod or MODIFIER_CTRL.toInt()
        if (event.isAltPressed) mod = mod or MODIFIER_ALT.toInt()
        if (event.isMetaPressed) mod = mod or MODIFIER_META.toInt()
        if (extended) mod = mod or MODIFIER_EXTENDED.toInt()
        return mod.toByte()
    }
}
