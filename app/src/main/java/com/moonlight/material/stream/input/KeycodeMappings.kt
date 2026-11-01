package com.moonlight.material.stream.input

import android.view.KeyEvent

object KeycodeMappings {
    // Windows Virtual Key (VK) codes
    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_SHIFT = 0x10
    const val VK_CONTROL = 0x11
    const val VK_MENU = 0x12 // Alt
    const val VK_PAUSE = 0x13
    const val VK_CAPITAL = 0x14 // Caps Lock
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_PRIOR = 0x21 // Page Up
    const val VK_NEXT = 0x22 // Page Down
    const val VK_END = 0x23
    const val VK_HOME = 0x24
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_SNAPSHOT = 0x2C // Print Screen
    const val VK_INSERT = 0x2D
    const val VK_DELETE = 0x2E
    const val VK_LWIN = 0x5B
    const val VK_RWIN = 0x5C

    fun androidKeyToWindowsVk(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> 0x41
            KeyEvent.KEYCODE_B -> 0x42
            KeyEvent.KEYCODE_C -> 0x43
            KeyEvent.KEYCODE_D -> 0x44
            KeyEvent.KEYCODE_E -> 0x45
            KeyEvent.KEYCODE_F -> 0x46
            KeyEvent.KEYCODE_G -> 0x47
            KeyEvent.KEYCODE_H -> 0x48
            KeyEvent.KEYCODE_I -> 0x49
            KeyEvent.KEYCODE_J -> 0x4A
            KeyEvent.KEYCODE_K -> 0x4B
            KeyEvent.KEYCODE_L -> 0x4C
            KeyEvent.KEYCODE_M -> 0x4D
            KeyEvent.KEYCODE_N -> 0x4E
            KeyEvent.KEYCODE_O -> 0x4F
            KeyEvent.KEYCODE_P -> 0x50
            KeyEvent.KEYCODE_Q -> 0x51
            KeyEvent.KEYCODE_R -> 0x52
            KeyEvent.KEYCODE_S -> 0x53
            KeyEvent.KEYCODE_T -> 0x54
            KeyEvent.KEYCODE_U -> 0x55
            KeyEvent.KEYCODE_V -> 0x56
            KeyEvent.KEYCODE_W -> 0x57
            KeyEvent.KEYCODE_X -> 0x58
            KeyEvent.KEYCODE_Y -> 0x59
            KeyEvent.KEYCODE_Z -> 0x5A

            KeyEvent.KEYCODE_0 -> 0x30
            KeyEvent.KEYCODE_1 -> 0x31
            KeyEvent.KEYCODE_2 -> 0x32
            KeyEvent.KEYCODE_3 -> 0x33
            KeyEvent.KEYCODE_4 -> 0x34
            KeyEvent.KEYCODE_5 -> 0x35
            KeyEvent.KEYCODE_6 -> 0x36
            KeyEvent.KEYCODE_7 -> 0x37
            KeyEvent.KEYCODE_8 -> 0x38
            KeyEvent.KEYCODE_9 -> 0x39

            KeyEvent.KEYCODE_DEL -> VK_BACK
            KeyEvent.KEYCODE_TAB -> VK_TAB
            KeyEvent.KEYCODE_ENTER -> VK_RETURN
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> VK_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> VK_MENU
            KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPITAL
            KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
            KeyEvent.KEYCODE_SPACE -> VK_SPACE
            KeyEvent.KEYCODE_PAGE_UP -> VK_PRIOR
            KeyEvent.KEYCODE_PAGE_DOWN -> VK_NEXT
            KeyEvent.KEYCODE_MOVE_END -> VK_END
            KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
            KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> VK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
            KeyEvent.KEYCODE_INSERT -> VK_INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> VK_DELETE
            KeyEvent.KEYCODE_F1 -> 0x70
            KeyEvent.KEYCODE_F2 -> 0x71
            KeyEvent.KEYCODE_F3 -> 0x72
            KeyEvent.KEYCODE_F4 -> 0x73
            KeyEvent.KEYCODE_F5 -> 0x74
            KeyEvent.KEYCODE_F6 -> 0x75
            KeyEvent.KEYCODE_F7 -> 0x76
            KeyEvent.KEYCODE_F8 -> 0x77
            KeyEvent.KEYCODE_F9 -> 0x78
            KeyEvent.KEYCODE_F10 -> 0x79
            KeyEvent.KEYCODE_F11 -> 0x7A
            KeyEvent.KEYCODE_F12 -> 0x7B

            KeyEvent.KEYCODE_MINUS -> 0xBD
            KeyEvent.KEYCODE_EQUALS -> 0xBB
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
            KeyEvent.KEYCODE_BACKSLASH -> 0xDC
            KeyEvent.KEYCODE_SEMICOLON -> 0xBA
            KeyEvent.KEYCODE_APOSTROPHE -> 0xDE
            KeyEvent.KEYCODE_GRAVE -> 0xC0
            KeyEvent.KEYCODE_COMMA -> 0xBC
            KeyEvent.KEYCODE_PERIOD -> 0xBE
            KeyEvent.KEYCODE_SLASH -> 0xBF

            else -> 0
        }
    }
}
