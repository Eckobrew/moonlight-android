package com.moonlight.material.stream.input

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.moonlight.material.core.utils.AppLogger
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

    fun onGamepadMenuRequested() {}

    fun onEmulatedMouseMove(deltaX: Short, deltaY: Short)
    fun onEmulatedMouseButton(button: Byte, isDown: Boolean)
    fun onEmulatedMouseScroll(scroll: Short)
}

class GamepadHandler(
    private val listener: GamepadListener,
    var swapFaceButtons: Boolean = false,
    var gamepadMouseEnabled: Boolean = true
) {
    companion object {
        const val BUTTON_HOLD_SECONDS = 3

        fun isGamepad(device: InputDevice): Boolean =
            !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                device.supportsSource(InputDevice.SOURCE_JOYSTICK))
    }

    private val TAG = "GAMEPAD"
    private var isMouseEmulationMode = false
    private val handler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    private var buttonFlags = 0
    private var hatFlags = 0
    private var leftTriggerPressed = false
    private var rightTriggerPressed = false
    private var leftMouseDown = false
    private var rightMouseDown = false
    private var leftTrigger: Byte = 0
    private var rightTrigger: Byte = 0
    private var leftStickX: Short = 0
    private var leftStickY: Short = 0
    private var rightStickX: Short = 0
    private var rightStickY: Short = 0

    // Holding Start toggles mouse emulation.
    private val startHoldRunnable = Runnable {
        if (!gamepadMouseEnabled) return@Runnable
        val enableMouseMode = !isMouseEmulationMode
        reset()
        isMouseEmulationMode = enableMouseMode
        if (enableMouseMode) handler?.postDelayed(mouseMotionRunnable, 50L)
        notifyState()
        AppLogger.info("Gamepad mouse emulation toggled: $isMouseEmulationMode", TAG)
    }

    private val backHoldRunnable = Runnable {
        reset(keepMouseMode = true)
        notifyState()
        listener.onGamepadMenuRequested()
    }

    private val mouseMotionRunnable = object : Runnable {
        override fun run() {
            if (!isMouseEmulationMode) return
            val deltaX = (leftStickX * 15 / 32767).toShort()
            val deltaY = (-leftStickY * 15 / 32767).toShort()
            if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                listener.onEmulatedMouseMove(deltaX, deltaY)
            }
            val scrollY = (rightStickY * 5 / 32767).toShort()
            if (scrollY.toInt() != 0) listener.onEmulatedMouseScroll(scrollY)
            handler?.postDelayed(this, 50L)
        }
    }

    fun reset(keepMouseMode: Boolean = false) {
        handler?.removeCallbacks(startHoldRunnable)
        handler?.removeCallbacks(backHoldRunnable)
        if (leftMouseDown) listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_LEFT, false)
        if (rightMouseDown) listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_RIGHT, false)
        leftMouseDown = false
        rightMouseDown = false
        if (!keepMouseMode) {
            isMouseEmulationMode = false
            handler?.removeCallbacks(mouseMotionRunnable)
        }
        buttonFlags = 0
        hatFlags = 0
        leftTriggerPressed = false
        rightTriggerPressed = false
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return false
        }

        val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
        val ly = getCenteredAxis(event, MotionEvent.AXIS_Y)
        val hasZStick = event.device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null &&
            event.device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null
        val rx = getCenteredAxis(event, if (hasZStick) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        val ry = getCenteredAxis(event, if (hasZStick) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it > 0 }
            ?: event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it > 0 }
            ?: event.getAxisValue(MotionEvent.AXIS_GAS)

        // D-Pad from Hat axes
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        hatFlags = 0
        if (hatY < -0.5f) hatFlags = hatFlags or ControllerButtonFlags.UP
        if (hatY > 0.5f) hatFlags = hatFlags or ControllerButtonFlags.DOWN
        if (hatX < -0.5f) hatFlags = hatFlags or ControllerButtonFlags.LEFT
        if (hatX > 0.5f) hatFlags = hatFlags or ControllerButtonFlags.RIGHT

        leftTrigger = (lt.coerceIn(0f, 1f) * 255).toInt().toByte()
        rightTrigger = (rt.coerceIn(0f, 1f) * 255).toInt().toByte()

        leftStickX = (lx * 32767).toInt().toShort()
        leftStickY = (-ly * 32767).toInt().toShort() // Inverted Y for GameStream
        rightStickX = (rx * 32767).toInt().toShort()
        rightStickY = (-ry * 32767).toInt().toShort()

        if (!isMouseEmulationMode) notifyState()
        return true
    }

    fun handleButton(keyCode: Int, isDown: Boolean, repeatCount: Int = 0): Boolean {
        // Check for hold-Start to toggle mouse emulation
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START && gamepadMouseEnabled) {
            if (isDown && repeatCount == 0) {
                handler?.postDelayed(startHoldRunnable, BUTTON_HOLD_SECONDS * 1000L)
            } else if (!isDown) {
                handler?.removeCallbacks(startHoldRunnable)
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (isDown && repeatCount == 0) {
                handler?.postDelayed(backHoldRunnable, BUTTON_HOLD_SECONDS * 1000L)
            } else if (!isDown) {
                handler?.removeCallbacks(backHoldRunnable)
            }
        }

        if (isMouseEmulationMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (leftMouseDown != isDown) {
                        leftMouseDown = isDown
                        listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_LEFT, isDown)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (rightMouseDown != isDown) {
                        rightMouseDown = isDown
                        listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_RIGHT, isDown)
                    }
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> leftTriggerPressed = isDown
            KeyEvent.KEYCODE_BUTTON_R2 -> rightTriggerPressed = isDown
            else -> {
                val buttonMask = mapKeyCodeToButton(keyCode) ?: return false
                buttonFlags = if (isDown) buttonFlags or buttonMask else buttonFlags and buttonMask.inv()
            }
        }

        if (!isMouseEmulationMode) notifyState()
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val triggerAxes = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> MotionEvent.AXIS_LTRIGGER to MotionEvent.AXIS_BRAKE
            KeyEvent.KEYCODE_BUTTON_R2 -> MotionEvent.AXIS_RTRIGGER to MotionEvent.AXIS_GAS
            else -> null
        }
        if (triggerAxes != null && event.device?.let { device ->
                device.getMotionRange(triggerAxes.first, InputDevice.SOURCE_JOYSTICK) != null ||
                    device.getMotionRange(triggerAxes.second, InputDevice.SOURCE_JOYSTICK) != null
            } == true) {
            return true
        }
        val keyCode = if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.flags and KeyEvent.FLAG_FALLBACK != 0) {
                KeyEvent.KEYCODE_BUTTON_B
            } else {
                KeyEvent.KEYCODE_BUTTON_SELECT
            }
        } else {
            event.keyCode
        }
        return handleButton(keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
    }

    private fun mapKeyCodeToButton(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> if (swapFaceButtons) ControllerButtonFlags.B else ControllerButtonFlags.A
            KeyEvent.KEYCODE_BUTTON_B -> if (swapFaceButtons) ControllerButtonFlags.A else ControllerButtonFlags.B
            KeyEvent.KEYCODE_BUTTON_X -> if (swapFaceButtons) ControllerButtonFlags.Y else ControllerButtonFlags.X
            KeyEvent.KEYCODE_BUTTON_Y -> if (swapFaceButtons) ControllerButtonFlags.X else ControllerButtonFlags.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerButtonFlags.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerButtonFlags.RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerButtonFlags.LS_CLK
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerButtonFlags.RS_CLK
            KeyEvent.KEYCODE_BUTTON_START -> ControllerButtonFlags.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerButtonFlags.BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> ControllerButtonFlags.SPECIAL
            KeyEvent.KEYCODE_DPAD_UP -> ControllerButtonFlags.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerButtonFlags.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerButtonFlags.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerButtonFlags.RIGHT
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
            buttonFlags = buttonFlags or hatFlags,
            leftTrigger = if (leftTriggerPressed) 255.toByte() else leftTrigger,
            rightTrigger = if (rightTriggerPressed) 255.toByte() else rightTrigger,
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY
        )
    }

    fun handleRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        AppLogger.debug("Rumble for controller $controllerNumber: low=$lowFreqMotor, high=$highFreqMotor", TAG)
    }

    fun handleRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        AppLogger.debug("Trigger rumble for controller $controllerNumber: L=$leftTrigger, R=$rightTrigger", TAG)
    }
}
