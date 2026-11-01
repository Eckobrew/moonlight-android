package com.moonlight.material.input

import android.view.KeyEvent
import com.moonlight.material.stream.input.KeycodeMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardProtocolTest {

    @Test
    fun ordinaryKeysUseNormalizedProtocolMapping() {
        val keysToTest = intArrayOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_SEMICOLON,
            KeyEvent.KEYCODE_BACKSLASH
        )

        for (code in keysToTest) {
            val key = KeycodeMappings.hostKey(code)
            assertNotNull("Mapping must exist for keycode $code", key)
            assertEquals(0.toByte(), key!!.flags)
            assertTrue("Wire code must have 0x8000 tag", (key.wireCode.toInt() and 0x8000) != 0)
        }

        assertEquals(0x5A.toShort(), KeycodeMappings.hostKey(KeyEvent.KEYCODE_Z)?.virtualKey)
        assertEquals(0x59.toShort(), KeycodeMappings.hostKey(KeyEvent.KEYCODE_Y)?.virtualKey)
        assertEquals(0x41.toShort(), KeycodeMappings.hostKey(KeyEvent.KEYCODE_A)?.virtualKey)
    }

    @Test
    fun keypadAndMainEnterRemainDistinct() {
        val main = KeycodeMappings.hostKey(KeyEvent.KEYCODE_ENTER)
        val keypad = KeycodeMappings.hostKey(KeyEvent.KEYCODE_NUMPAD_ENTER)

        assertNotNull(main)
        assertNotNull(keypad)

        assertEquals(main!!.virtualKey, keypad!!.virtualKey)
        assertEquals(KeycodeMappings.VK_RETURN, main.virtualKey)
        assertFalse(main.extended)
        assertTrue(keypad.extended)
        assertTrue(main != keypad)

        assertEquals(KeycodeMappings.VK_NUMPAD0, KeycodeMappings.hostKey(KeyEvent.KEYCODE_NUMPAD_0)?.virtualKey)
        assertEquals(KeycodeMappings.VK_NUMPAD9, KeycodeMappings.hostKey(KeyEvent.KEYCODE_NUMPAD_9)?.virtualKey)
        assertEquals(true, KeycodeMappings.hostKey(KeyEvent.KEYCODE_NUMPAD_DIVIDE)?.extended)
    }

    @Test
    fun rightModifiersRetainExtendedFlagAfterRemapping() {
        // Right Alt (AltGr)
        val rAlt = KeycodeMappings.hostKey(KeyEvent.KEYCODE_ALT_RIGHT, swapWinAltKeys = false)
        assertNotNull(rAlt)
        assertEquals(KeycodeMappings.VK_RMENU, rAlt!!.virtualKey)
        assertEquals(KeycodeMappings.MODIFIER_EXTENDED, rAlt.extendedModifier)

        // Right Ctrl
        val rCtrl = KeycodeMappings.hostKey(KeyEvent.KEYCODE_CTRL_RIGHT)
        assertNotNull(rCtrl)
        assertTrue(rCtrl!!.extended)

        // Left Ctrl
        val lCtrl = KeycodeMappings.hostKey(KeyEvent.KEYCODE_CTRL_LEFT)
        assertNotNull(lCtrl)
        assertFalse(lCtrl!!.extended)

        // Swap Win and Alt keys
        val swappedMeta = KeycodeMappings.hostKey(KeyEvent.KEYCODE_META_RIGHT, swapWinAltKeys = true)
        assertEquals(KeycodeMappings.VK_RMENU, swappedMeta?.virtualKey)

        val swappedAlt = KeycodeMappings.hostKey(KeyEvent.KEYCODE_ALT_LEFT, swapWinAltKeys = true)
        assertEquals(KeycodeMappings.VK_LWIN, swappedAlt?.virtualKey)

        // Arrows are extended
        assertTrue(KeycodeMappings.hostKey(KeyEvent.KEYCODE_DPAD_LEFT)?.extended == true)
        assertTrue(KeycodeMappings.hostKey(KeyEvent.KEYCODE_DPAD_RIGHT)?.extended == true)
    }

    @Test
    fun unmappedKeysReturnNull() {
        assertNull(KeycodeMappings.hostKey(KeyEvent.KEYCODE_UNKNOWN))
    }
}
