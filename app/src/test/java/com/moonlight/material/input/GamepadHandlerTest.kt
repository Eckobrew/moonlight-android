package com.moonlight.material.input

import android.view.KeyEvent
import com.moonlight.material.stream.input.ControllerButtonFlags
import com.moonlight.material.stream.input.GamepadHandler
import com.moonlight.material.stream.input.GamepadListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadHandlerTest {

    private class TestGamepadListener : GamepadListener {
        var lastButtonFlags: Int = 0
        var lastLeftTrigger: Byte = 0
        var lastRightTrigger: Byte = 0
        var lastLeftStickX: Short = 0
        var lastLeftStickY: Short = 0
        var lastRightStickX: Short = 0
        var lastRightStickY: Short = 0

        override fun onControllerStateChanged(
            controllerNumber: Short,
            buttonFlags: Int,
            leftTrigger: Byte,
            rightTrigger: Byte,
            leftStickX: Short,
            leftStickY: Short,
            rightStickX: Short,
            rightStickY: Short
        ) {
            lastButtonFlags = buttonFlags
            lastLeftTrigger = leftTrigger
            lastRightTrigger = rightTrigger
            lastLeftStickX = leftStickX
            lastLeftStickY = leftStickY
            lastRightStickX = rightStickX
            lastRightStickY = rightStickY
        }

        override fun onEmulatedMouseMove(deltaX: Short, deltaY: Short) {}
        override fun onEmulatedMouseButton(button: Byte, isDown: Boolean) {}
        override fun onEmulatedMouseScroll(scroll: Short) {}
    }

    @Test
    fun buttonPressAndReleaseUpdatesFlags() {
        val listener = TestGamepadListener()
        val handler = GamepadHandler(listener)

        assertTrue(handler.handleButton(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(ControllerButtonFlags.A, listener.lastButtonFlags)

        assertTrue(handler.handleButton(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(0, listener.lastButtonFlags)
    }

    @Test
    fun faceButtonSwapping() {
        val listener = TestGamepadListener()
        val handler = GamepadHandler(listener, swapFaceButtons = true)

        // With swapFaceButtons: KEYCODE_BUTTON_A maps to B
        handler.handleButton(KeyEvent.KEYCODE_BUTTON_A, true)
        assertEquals(ControllerButtonFlags.B, listener.lastButtonFlags)

        // KEYCODE_BUTTON_B maps to A
        handler.handleButton(KeyEvent.KEYCODE_BUTTON_B, true)
        assertEquals(ControllerButtonFlags.A or ControllerButtonFlags.B, listener.lastButtonFlags)

        // KEYCODE_BUTTON_X maps to Y
        val handler2 = GamepadHandler(listener, swapFaceButtons = true)
        handler2.handleButton(KeyEvent.KEYCODE_BUTTON_X, true)
        assertEquals(ControllerButtonFlags.Y, listener.lastButtonFlags)
    }

    @Test
    fun dpadButtonsMapping() {
        val listener = TestGamepadListener()
        val handler = GamepadHandler(listener)

        handler.handleButton(KeyEvent.KEYCODE_DPAD_UP, true)
        assertEquals(ControllerButtonFlags.UP, listener.lastButtonFlags)

        handler.handleButton(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        assertEquals(ControllerButtonFlags.UP or ControllerButtonFlags.RIGHT, listener.lastButtonFlags)
    }

    @Test
    fun rumbleHandlerExecutesWithoutCrashing() {
        val listener = TestGamepadListener()
        val handler = GamepadHandler(listener)
        handler.handleRumble(0, 32000.toShort(), 16000.toShort())
        handler.handleRumbleTriggers(0, 1000.toShort(), 2000.toShort())
    }
}
