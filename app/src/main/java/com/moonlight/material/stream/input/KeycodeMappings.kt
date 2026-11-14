package com.moonlight.material.stream.input

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent

/**
 * Representation of a key ready to be sent to the GameStream host.
 */
data class HostKey(
    val virtualKey: Short,
    val extended: Boolean,
    val flags: Byte = 0,
    val impliedModifiers: Byte = 0
) {
    // The GameStream tag accompanies a VK code: virtualKey | 0x8000
    val wireCode: Short
        get() = (virtualKey.toInt() or 0x8000).toShort()

    val extendedModifier: Byte
        get() = if (extended) KeycodeMappings.MODIFIER_EXTENDED else 0
}

/**
 * Android KeyEvent to Windows Virtual Key (VK) mapping, aligned with
 * upstream moonlight-android KeyboardTranslator.java and Game.java.
 *
 * Includes:
 * - Android 13+ (API 33) physical keyboard layout normalization via InputDevice.getKeyCodeForKeyLocation
 * - F1-F24, Print Screen / Screenshot (Android 15/16), KEYCODE_PLUS (implied Shift + '=')
 * - Swap Windows and Alt keys (both virtual keys and modifier masks)
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
    const val VK_PRINT: Short      = 0x2A
    const val VK_SNAPSHOT: Short   = 0x2C // Print Screen / Screenshot
    const val VK_INSERT: Short     = 0x2D
    const val VK_DELETE: Short     = 0x2E
    const val VK_LWIN: Short       = 0x5B // Left Windows Key
    const val VK_RWIN: Short       = 0x5C // Right Windows Key
    const val VK_APPS: Short       = 0x5D // Context Menu

    // Left/right-specific modifier codes
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

    // Modifier flags (Limelight.h)
    const val MODIFIER_SHIFT: Byte    = 0x01
    const val MODIFIER_CTRL: Byte     = 0x02
    const val MODIFIER_ALT: Byte      = 0x04
    const val MODIFIER_META: Byte     = 0x08
    const val MODIFIER_EXTENDED: Byte = 0x10

    // Sunshine protocol extension flag
    const val SS_KBE_FLAG_NON_NORMALIZED: Byte = 0x01

    // Android 15/16 KEYCODE_PRINT (311) & KEYCODE_SCREENSHOT (318)
    private const val KEYCODE_PRINT_COMPAT = 311
    private const val KEYCODE_SCREENSHOT_COMPAT = 318

    private val extendedVirtualKeys: Set<Short> = setOf(
        VK_PRIOR, VK_NEXT, VK_END, VK_HOME,
        VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN,
        VK_INSERT, VK_DELETE, VK_LWIN, VK_RWIN,
        VK_DIVIDE, VK_RCONTROL, VK_RMENU, VK_APPS
    )

    // Cache of deviceId -> (deviceKeyCode -> qwertyLocationKeyCode) for Android 13+ layout normalization
    private val deviceMappings = HashMap<Int, IntArray>()

    @Synchronized
    private fun getNormalizedKeyCode(deviceId: Int, keyCode: Int): Pair<Int, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || deviceId <= 0) {
            return keyCode to false
        }
        val device = try {
            InputDevice.getDevice(deviceId)
        } catch (_: Throwable) {
            null
        } ?: return keyCode to false

        if (device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return keyCode to false
        }

        val mapping = deviceMappings.getOrPut(deviceId) {
            val table = IntArray(KeyEvent.getMaxKeyCode() + 1)
            for (i in 0..KeyEvent.getMaxKeyCode()) {
                val translated = translateKeyCodeInternal(i, false)
                if (translated != null) {
                    val mappedKeyCode = device.getKeyCodeForKeyLocation(i)
                    if (mappedKeyCode in table.indices && mappedKeyCode != KeyEvent.KEYCODE_UNKNOWN && table[mappedKeyCode] == 0) {
                        table[mappedKeyCode] = i
                    }
                }
            }
            table
        }

        if (keyCode in mapping.indices && mapping[keyCode] != 0) {
            return mapping[keyCode] to true
        }
        return keyCode to false
    }

    /**
     * Translates an Android keycode (and optional deviceId for layout normalization) into a HostKey.
     */
    fun hostKey(
        androidKeyCode: Int,
        swapWinAltKeys: Boolean = false,
        deviceId: Int = -1
    ): HostKey? {
        val (normalizedKeyCode, wasNormalizedByDevice) = getNormalizedKeyCode(deviceId, androidKeyCode)
        val result = translateKeyCodeInternal(normalizedKeyCode, swapWinAltKeys) ?: return null
        val (vk, isExplicitExtended, impliedMods) = result
        val extended = isExplicitExtended || extendedVirtualKeys.contains(vk)
        val flags: Byte = if (deviceId > 0 && !wasNormalizedByDevice) {
            SS_KBE_FLAG_NON_NORMALIZED
        } else {
            0
        }
        return HostKey(
            virtualKey = vk,
            extended = extended,
            flags = flags,
            impliedModifiers = impliedMods
        )
    }

    private fun translateKeyCodeInternal(
        androidKeyCode: Int,
        swapWinAltKeys: Boolean
    ): Triple<Short, Boolean, Byte>? {
        return when (androidKeyCode) {
            // Letters A-Z (0x41 - 0x5A)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                Triple((0x41 + (androidKeyCode - KeyEvent.KEYCODE_A)).toShort(), false, 0)
            }

            // Digits 0-9 (0x30 - 0x39)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                Triple((0x30 + (androidKeyCode - KeyEvent.KEYCODE_0)).toShort(), false, 0)
            }

            // Function keys F1-F12 (0x70 - 0x7B)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                Triple((0x70 + (androidKeyCode - KeyEvent.KEYCODE_F1)).toShort(), false, 0)
            }

            // Function keys F13-F24 (0x7C - 0x87, keycodes 320..331 on Android)
            in 320..331 -> {
                Triple((0x7C + (androidKeyCode - 320)).toShort(), false, 0)
            }

            // Numpad 0-9
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                Triple((VK_NUMPAD0 + (androidKeyCode - KeyEvent.KEYCODE_NUMPAD_0)).toShort(), false, 0)
            }

            // Enter distinction
            KeyEvent.KEYCODE_ENTER -> Triple(VK_RETURN, false, 0)
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Triple(VK_RETURN, true, 0)

            // Navigation & Editing
            KeyEvent.KEYCODE_DEL -> Triple(VK_BACK, false, 0)
            KeyEvent.KEYCODE_FORWARD_DEL -> Triple(VK_DELETE, true, 0)
            KeyEvent.KEYCODE_TAB -> Triple(VK_TAB, false, 0)
            KeyEvent.KEYCODE_SPACE -> Triple(VK_SPACE, false, 0)
            KeyEvent.KEYCODE_ESCAPE -> Triple(VK_ESCAPE, false, 0)
            KeyEvent.KEYCODE_CAPS_LOCK -> Triple(VK_CAPITAL, false, 0)
            KeyEvent.KEYCODE_CLEAR -> Triple(VK_CLEAR, false, 0)
            KeyEvent.KEYCODE_SCROLL_LOCK -> Triple(VK_SCROLL, false, 0)
            KeyEvent.KEYCODE_NUM_LOCK -> Triple(VK_NUMLOCK, false, 0)
            KeyEvent.KEYCODE_PAGE_UP -> Triple(VK_PRIOR, true, 0)
            KeyEvent.KEYCODE_PAGE_DOWN -> Triple(VK_NEXT, true, 0)
            KeyEvent.KEYCODE_MOVE_HOME -> Triple(VK_HOME, true, 0)
            KeyEvent.KEYCODE_MOVE_END -> Triple(VK_END, true, 0)
            KeyEvent.KEYCODE_INSERT -> Triple(VK_INSERT, true, 0)
            KeyEvent.KEYCODE_BREAK -> Triple(VK_PAUSE, false, 0)
            KEYCODE_PRINT_COMPAT -> Triple(VK_PRINT, false, 0)
            KeyEvent.KEYCODE_SYSRQ, KEYCODE_SCREENSHOT_COMPAT -> Triple(VK_SNAPSHOT, false, 0)

            // Arrows
            KeyEvent.KEYCODE_DPAD_LEFT -> Triple(VK_LEFT, true, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Triple(VK_RIGHT, true, 0)
            KeyEvent.KEYCODE_DPAD_UP -> Triple(VK_UP, true, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> Triple(VK_DOWN, true, 0)

            // Modifiers
            KeyEvent.KEYCODE_SHIFT_LEFT -> Triple(VK_LSHIFT, false, 0)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Triple(VK_RSHIFT, false, 0)
            KeyEvent.KEYCODE_CTRL_LEFT -> Triple(VK_LCONTROL, false, 0)
            KeyEvent.KEYCODE_CTRL_RIGHT -> Triple(VK_RCONTROL, true, 0)
            KeyEvent.KEYCODE_ALT_LEFT -> Triple(
                if (swapWinAltKeys) VK_LWIN else VK_LMENU,
                swapWinAltKeys,
                0
            )
            KeyEvent.KEYCODE_ALT_RIGHT -> Triple(
                if (swapWinAltKeys) VK_RWIN else VK_RMENU,
                true,
                0
            )
            KeyEvent.KEYCODE_META_LEFT -> Triple(
                if (swapWinAltKeys) VK_LMENU else VK_LWIN,
                !swapWinAltKeys,
                0
            )
            KeyEvent.KEYCODE_META_RIGHT -> Triple(
                if (swapWinAltKeys) VK_RMENU else VK_RWIN,
                true,
                0
            )
            KeyEvent.KEYCODE_MENU -> Triple(VK_APPS, true, 0)

            // Punctuation & Symbols
            KeyEvent.KEYCODE_MINUS -> Triple(0xBD.toShort(), false, 0)
            KeyEvent.KEYCODE_EQUALS -> Triple(0xBB.toShort(), false, 0)
            KeyEvent.KEYCODE_PLUS -> Triple(0xBB.toShort(), false, MODIFIER_SHIFT)
            KeyEvent.KEYCODE_LEFT_BRACKET -> Triple(0xDB.toShort(), false, 0)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Triple(0xDD.toShort(), false, 0)
            KeyEvent.KEYCODE_BACKSLASH -> Triple(0xDC.toShort(), false, 0)
            KeyEvent.KEYCODE_SEMICOLON -> Triple(0xBA.toShort(), false, 0)
            KeyEvent.KEYCODE_APOSTROPHE -> Triple(0xDE.toShort(), false, 0)
            KeyEvent.KEYCODE_GRAVE -> Triple(0xC0.toShort(), false, 0)
            KeyEvent.KEYCODE_COMMA -> Triple(0xBC.toShort(), false, 0)
            KeyEvent.KEYCODE_PERIOD -> Triple(0xBE.toShort(), false, 0)
            KeyEvent.KEYCODE_SLASH -> Triple(0xBF.toShort(), false, 0)
            KeyEvent.KEYCODE_NUMPAD_COMMA -> Triple(VK_SEPARATOR, false, 0)

            // Numpad Math
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> Triple(VK_DIVIDE, true, 0)
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> Triple(VK_MULTIPLY, false, 0)
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Triple(VK_SUBTRACT, false, 0)
            KeyEvent.KEYCODE_NUMPAD_ADD -> Triple(VK_ADD, false, 0)
            KeyEvent.KEYCODE_NUMPAD_DOT -> Triple(VK_DECIMAL, false, 0)

            else -> null
        }
    }

    /**
     * Extracts wire modifier flags from an Android KeyEvent, honoring swapWinAltKeys.
     */
    fun extractModifiers(
        event: KeyEvent,
        extended: Boolean = false,
        swapWinAltKeys: Boolean = false,
        trackedModifiers: Int = 0,
        impliedModifiers: Byte = 0
    ): Byte {
        var mod: Int = trackedModifiers or (impliedModifiers.toInt() and 0xFF)
        if (event.isShiftPressed) mod = mod or MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) mod = mod or MODIFIER_CTRL.toInt()
        if (event.isAltPressed) {
            mod = mod or (if (swapWinAltKeys) MODIFIER_META.toInt() else MODIFIER_ALT.toInt())
        }
        if (event.isMetaPressed) {
            mod = mod or (if (swapWinAltKeys) MODIFIER_ALT.toInt() else MODIFIER_META.toInt())
        }
        return mod.toByte()
    }
}
