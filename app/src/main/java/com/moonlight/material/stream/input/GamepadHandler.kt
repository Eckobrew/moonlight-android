package com.moonlight.material.stream.input

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.stream.control.ControlProtocol
import kotlin.math.abs

interface GamepadListener {
    fun onControllerStateChanged(
        controllerNumber: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    )

    fun onEmulatedMouseMove(deltaX: Short, deltaY: Short)
    fun onEmulatedMouseButton(button: Byte, isDown: Boolean)
    fun onEmulatedMouseScroll(scroll: Short)
}

class GamepadHandler(
    private val listener: GamepadListener,
    var swapFaceButtons: Boolean = false,
    var gamepadMouseEnabled: Boolean = true
) {
    private val TAG = "GAMEPAD"
    private var isMouseEmulationMode = false
    private val handler = Handler(Looper.getMainLooper())

    private var buttonFlags = 0
    private var leftTrigger: Byte = 0
    private var rightTrigger: Byte = 0
    private var leftStickX: Short = 0
    private var leftStickY: Short = 0
    private var rightStickX: Short = 0
    private var rightStickY: Short = 0

    // Holding start for 1 second toggles mouse emulation
    private val startHoldRunnable = Runnable {
        isMouseEmulationMode = !isMouseEmulationMode
        AppLogger.info("Gamepad mouse emulation toggled: $isMouseEmulationMode", TAG)
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
        val ly = getCenteredAxis(event, MotionEvent.AXIS_Y)
        val rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
        val ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)

        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it > 0 }
            ?: event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it > 0 }
            ?: event.getAxisValue(MotionEvent.AXIS_GAS)

        // D-Pad from Hat axes
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        buttonFlags = buttonFlags and (ControlProtocol.BUTTON_UP or ControlProtocol.BUTTON_DOWN or ControlProtocol.BUTTON_LEFT or ControlProtocol.BUTTON_RIGHT).inv()
        if (hatY < -0.5f) buttonFlags = buttonFlags or ControlProtocol.BUTTON_UP
        if (hatY > 0.5f) buttonFlags = buttonFlags or ControlProtocol.BUTTON_DOWN
        if (hatX < -0.5f) buttonFlags = buttonFlags or ControlProtocol.BUTTON_LEFT
        if (hatX > 0.5f) buttonFlags = buttonFlags or ControlProtocol.BUTTON_RIGHT

        leftTrigger = (lt.coerceIn(0f, 1f) * 255).toInt().toByte()
        rightTrigger = (rt.coerceIn(0f, 1f) * 255).toInt().toByte()

        leftStickX = (lx * 32767).toInt().toShort()
        leftStickY = (-ly * 32767).toInt().toShort() // Inverted Y for GameStream
        rightStickX = (rx * 32767).toInt().toShort()
        rightStickY = (-ry * 32767).toInt().toShort()

        if (isMouseEmulationMode) {
            val deltaX = (lx * 15).toInt().toShort()
            val deltaY = (ly * 15).toInt().toShort()
            if (abs(deltaX.toInt()) > 0 || abs(deltaY.toInt()) > 0) {
                listener.onEmulatedMouseMove(deltaX, deltaY)
            }
            val scrollY = (-ry * 5).toInt().toShort()
            if (abs(scrollY.toInt()) > 0) {
                listener.onEmulatedMouseScroll(scrollY)
            }
            return true
        }

        notifyState()
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // Check for hold-Start to toggle mouse emulation
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START && gamepadMouseEnabled) {
            if (isDown && event.repeatCount == 0) {
                handler.postDelayed(startHoldRunnable, 1000L)
            } else if (!isDown) {
                handler.removeCallbacks(startHoldRunnable)
            }
        }

        if (isMouseEmulationMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    listener.onEmulatedMouseButton(ControlProtocol.BUTTON_LEFT_MOUSE.toByte(), isDown)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    listener.onEmulatedMouseButton(ControlProtocol.BUTTON_RIGHT_MOUSE.toByte(), isDown)
                    return true
                }
            }
        }

        val buttonMask = mapKeyCodeToButton(event.keyCode) ?: return false

        buttonFlags = if (isDown) {
            buttonFlags or buttonMask
        } else {
            buttonFlags and buttonMask.inv()
        }

        notifyState()
        return true
    }

    private fun mapKeyCodeToButton(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> if (swapFaceButtons) ControlProtocol.BUTTON_B else ControlProtocol.BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B -> if (swapFaceButtons) ControlProtocol.BUTTON_A else ControlProtocol.BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X -> if (swapFaceButtons) ControlProtocol.BUTTON_Y else ControlProtocol.BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y -> if (swapFaceButtons) ControlProtocol.BUTTON_X else ControlProtocol.BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> ControlProtocol.BUTTON_LB
            KeyEvent.KEYCODE_BUTTON_R1 -> ControlProtocol.BUTTON_RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControlProtocol.BUTTON_LS
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControlProtocol.BUTTON_RS
            KeyEvent.KEYCODE_BUTTON_START -> ControlProtocol.BUTTON_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControlProtocol.BUTTON_BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> ControlProtocol.BUTTON_GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> ControlProtocol.BUTTON_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControlProtocol.BUTTON_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControlProtocol.BUTTON_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControlProtocol.BUTTON_RIGHT
            else -> null
        }
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        val flat = range.flat
        return if (abs(value) > flat) value else 0f
    }

    private fun notifyState() {
        listener.onControllerStateChanged(
            controllerNumber = 0,
            buttonFlags = buttonFlags,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY
        )
    }
}
