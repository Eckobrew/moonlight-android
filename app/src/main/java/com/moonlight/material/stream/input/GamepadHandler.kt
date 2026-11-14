package com.moonlight.material.stream.input

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.models.GamepadScrollStick
import com.moonlight.material.core.utils.AppLogger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    fun onGamepadQuitRequested() {}
    fun onGamepadMouseModeToggled(enabled: Boolean) {}
    fun onControllerTouchpadEvent(
        controllerNumber: Short,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float
    ) {}

    fun onEmulatedMouseMove(deltaX: Short, deltaY: Short)
    fun onEmulatedMouseButton(button: Byte, isDown: Boolean)
    fun onEmulatedMouseScroll(scroll: Short)
    fun onEmulatedMouseHorizontalScroll(scroll: Short) {}
    fun onEmulatedMouseDiscreteScroll(clicks: Byte) {}
    fun onEmulatedMouseDiscreteHScroll(clicks: Byte) {}
}

/**
 * Physical gamepad handler aligned with upstream moonlight-android ControllerHandler.java
 * and user commits (MouseEmulationHandler.java, 586bbe89, a9c0d868):
 * - Real joystick axis verification (`AXIS_X` and `AXIS_Y`) in `isGamepad`
 * - Accurate axis mapping for Right Stick (`AXIS_RX`/`RY` vs `AXIS_Z`/`RZ`) and Triggers
 *   (`LTRIGGER`/`RTRIGGER`, `BRAKE`/`GAS`, `BRAKE`/`THROTTLE`, or `Z`/`RZ` with `triggersIdleNegative`)
 * - Radial stick deadzone (configurable, default 7%) and trigger deadzone (13%)
 * - Extended Sunshine buttons: Xbox Series Share (`MISC`), DualShock/DualSense touchpad click (`TOUCHPAD`),
 *   Xbox Elite / DualSense Edge paddles (`PADDLE1..4` via evdev scancodes `0x2c4..0x2c7`), diagonal D-pad, `KEYCODE_MENU`
 * - Button combos: `Start + Select + LB + RB` quit combo, `Start + Select` Guide/Special emulation,
 *   and hold-Select menu shortcut
 * - 120Hz smooth cubic `MouseEmulationHandler` with 750ms Start hold, configurable sensitivity,
 *   sub-pixel accumulation, Y/stick-click middle click, and D-pad scrolling
 * - DualShock 4 / DualSense touchpad motion & clickpad forwarding (`tryHandleTouchpadEvent`)
 */
class GamepadHandler(
    private val listener: GamepadListener,
    var swapFaceButtons: Boolean = false,
    var gamepadMouseEnabled: Boolean = true,
    private val controllerNumber: Short = 0,
    device: InputDevice? = null
) {
    companion object {
        const val START_HOLD_MOUSE_MODE_MS = 750L
        const val SELECT_HOLD_MENU_MS = 1500L
        private const val MOUSE_EMULATION_TICK_RATE_HZ = 120
        private const val RAW_STICK_AXIS_MAX = 32766.0f
        private const val MOUSE_MOVE_BASE_SPEED_PX_PER_S = 1200.0f

        private const val EMULATING_SELECT = 0x1
        private const val EMULATING_SPECIAL = 0x2
        private const val EMULATING_TOUCHPAD = 0x4

        fun getMotionRangeForJoystickAxis(device: InputDevice, axis: Int): InputDevice.MotionRange? {
            return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
        }

        fun isGamepad(device: InputDevice): Boolean {
            if (device.isVirtual) return false
            val sources = device.sources
            val hasGamepadOrJoystick = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!hasGamepadOrJoystick) return false
            // Require real X and Y joystick axes so mice, keyboards, and TV remotes with
            // dummy joystick flags do not steal Player 1 or hide the on-screen controller.
            return getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
                getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
        }
    }

    private val TAG = "GAMEPAD"
    var scrollStick = GamepadScrollStick.RIGHT
    var mouseEmulationSensitivity: Int = 100
    var stickDeadzonePercent: Int = 7
    var hasPaddles: Boolean = false
    var hasShare: Boolean = false
    var hasSelect: Boolean = false
    var hasMode: Boolean = false
    var needsClickpadEmulation: Boolean = false

    // Configured axis IDs for the bound InputDevice
    private var leftStickXAxis = MotionEvent.AXIS_X
    private var leftStickYAxis = MotionEvent.AXIS_Y
    private var rightStickXAxis = MotionEvent.AXIS_Z
    private var rightStickYAxis = MotionEvent.AXIS_RZ
    private var leftTriggerAxis = MotionEvent.AXIS_LTRIGGER
    private var rightTriggerAxis = MotionEvent.AXIS_RTRIGGER
    private var triggersIdleNegative = false
    private var leftTriggerAxisUsed = false
    private var rightTriggerAxisUsed = false
    private var hatXAxisUsed = false
    private var hatYAxisUsed = false
    private var triggerDeadzone = 0.13f
    private var isNonStandardDualShock4 = false
    private var usesLinuxGamepadStandardFaceButtons = false
    private var touchpadXRange: InputDevice.MotionRange? = null
    private var touchpadYRange: InputDevice.MotionRange? = null
    private var touchpadPressureRange: InputDevice.MotionRange? = null

    init {
        if (device != null) {
            configureForDevice(device)
        }
    }

    fun configureForDevice(device: InputDevice) {
        val vid = device.vendorId
        val pid = device.productId
        try {
            hasPaddles = MoonBridge.guessControllerHasPaddles(vid, pid)
            hasShare = MoonBridge.guessControllerHasShareButton(vid, pid)
        } catch (_: Throwable) {
        }

        val hasKeySelect = try { device.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0] } catch (_: Throwable) { false }
        val hasKeyMode = try { device.hasKeys(KeyEvent.KEYCODE_BUTTON_MODE)[0] } catch (_: Throwable) { false }
        hasSelect = hasKeySelect
        hasMode = hasKeyMode

        // Trigger & Right Stick axis resolution matching upstream ControllerHandler.createInputDeviceContextForDevice
        val brakeRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_BRAKE)
        val gasRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_GAS)
        val throttleRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_THROTTLE)
        val ltRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_LTRIGGER)
        val rtRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_RTRIGGER)

        when {
            ltRange != null && rtRange != null -> {
                leftTriggerAxis = MotionEvent.AXIS_LTRIGGER
                rightTriggerAxis = MotionEvent.AXIS_RTRIGGER
                triggersIdleNegative = false
            }
            brakeRange != null && gasRange != null -> {
                leftTriggerAxis = MotionEvent.AXIS_BRAKE
                rightTriggerAxis = MotionEvent.AXIS_GAS
                triggersIdleNegative = false
            }
            brakeRange != null && throttleRange != null -> {
                leftTriggerAxis = MotionEvent.AXIS_BRAKE
                rightTriggerAxis = MotionEvent.AXIS_THROTTLE
                triggersIdleNegative = false
            }
            else -> {
                val rxRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_RX)
                val ryRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_RY)
                if (rxRange != null && ryRange != null && device.name != null) {
                    if (vid == 0x054c) { // Sony
                        if (try { device.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0] } catch (_: Throwable) { false }) {
                            isNonStandardDualShock4 = true
                            leftTriggerAxis = MotionEvent.AXIS_RX
                            rightTriggerAxis = MotionEvent.AXIS_RY
                            triggersIdleNegative = true
                        } else {
                            usesLinuxGamepadStandardFaceButtons = true
                            rightStickXAxis = MotionEvent.AXIS_RX
                            rightStickYAxis = MotionEvent.AXIS_RY
                            leftTriggerAxis = MotionEvent.AXIS_Z
                            rightTriggerAxis = MotionEvent.AXIS_RZ
                            triggersIdleNegative = true
                        }
                    } else {
                        // Standard Linux/Xbox fallback: RX/RY are Right Stick, Z/RZ are Triggers
                        rightStickXAxis = MotionEvent.AXIS_RX
                        rightStickYAxis = MotionEvent.AXIS_RY
                        leftTriggerAxis = MotionEvent.AXIS_Z
                        rightTriggerAxis = MotionEvent.AXIS_RZ
                        triggersIdleNegative = true
                    }
                }
            }
        }

        // If rightStickXAxis wasn't assigned to RX/RY above, use Z/RZ when present, else RX/RY
        if (rightStickXAxis != MotionEvent.AXIS_RX || leftTriggerAxis != MotionEvent.AXIS_Z) {
            val zRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Z)
            val rzRange = getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_RZ)
            if (zRange != null && rzRange != null) {
                rightStickXAxis = MotionEvent.AXIS_Z
                rightStickYAxis = MotionEvent.AXIS_RZ
            } else {
                rightStickXAxis = MotionEvent.AXIS_RX
                rightStickYAxis = MotionEvent.AXIS_RY
            }
        }

        val resolvedLtRange = getMotionRangeForJoystickAxis(device, leftTriggerAxis)
        val resolvedRtRange = getMotionRangeForJoystickAxis(device, rightTriggerAxis)
        if (resolvedLtRange != null && resolvedRtRange != null) {
            val flat = max(abs(resolvedLtRange.flat), abs(resolvedRtRange.flat))
            triggerDeadzone = if (flat < 0.13f || flat > 0.30f) 0.13f else flat
        }

        if ((device.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            touchpadXRange = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
            touchpadYRange = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
            touchpadPressureRange = device.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)
        }
    }

    private var isMouseEmulationMode = false
    private var lastMouseInputMap = 0
    private var lastTickTimeNs = 0L
    private var mouseMoveAccumX = 0f
    private var mouseMoveAccumY = 0f
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var startDownTime = 0L
    private var selectDownTime = 0L
    private var selectHoldConsumed = false
    private var emulatingButtonFlags = 0
    private var pendingExit = false

    private val handler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    private var buttonFlags = 0
    private var hatFlags = 0
    private var touchpadClickDown = false
    private var leftTriggerPressed = false
    private var rightTriggerPressed = false
    private var leftTrigger: Byte = 0
    private var rightTrigger: Byte = 0
    private var leftStickX: Short = 0
    private var leftStickY: Short = 0
    private var rightStickX: Short = 0
    private var rightStickY: Short = 0

    // Holding Start for 750ms toggles mouse emulation (matches user's MouseEmulationHandler)
    private val startHoldRunnable = Runnable {
        if (!gamepadMouseEnabled) return@Runnable
        toggleMouseEmulation()
    }

    // Holding Select opens the session controls menu without leaking Select held forever
    private val backHoldRunnable = Runnable {
        selectHoldConsumed = true
        buttonFlags = buttonFlags and ControllerButtonFlags.BACK.inv()
        notifyState()
        listener.onGamepadMenuRequested()
    }

    private val mouseMotionRunnable = object : Runnable {
        override fun run() {
            if (!isMouseEmulationMode) return
            val now = System.nanoTime()
            val deltaTimeSec = ((now - lastTickTimeNs) / 1_000_000_000.0f).coerceIn(0f, 0.1f)
            lastTickTimeNs = now

            when (scrollStick) {
                GamepadScrollStick.RIGHT -> {
                    sendEmulatedMouseMove(leftStickX, leftStickY, deltaTimeSec)
                    sendEmulatedMouseScroll(rightStickX, rightStickY, deltaTimeSec)
                }
                GamepadScrollStick.LEFT -> {
                    sendEmulatedMouseMove(rightStickX, rightStickY, deltaTimeSec)
                    sendEmulatedMouseScroll(leftStickX, leftStickY, deltaTimeSec)
                }
                GamepadScrollStick.NONE -> {
                    sendEmulatedMouseMove(leftStickX, leftStickY, deltaTimeSec)
                    sendEmulatedMouseMove(rightStickX, rightStickY, deltaTimeSec)
                }
            }
            handler?.postDelayed(this, 1000L / MOUSE_EMULATION_TICK_RATE_HZ)
        }
    }

    private fun toggleMouseEmulation() {
        handler?.removeCallbacks(mouseMotionRunnable)
        isMouseEmulationMode = !isMouseEmulationMode
        buttonFlags = buttonFlags and ControllerButtonFlags.START.inv()
        if (isMouseEmulationMode) {
            lastTickTimeNs = System.nanoTime()
            mouseMoveAccumX = 0f
            mouseMoveAccumY = 0f
            scrollAccumX = 0f
            scrollAccumY = 0f
            lastMouseInputMap = 0
            // Clear active game buttons on host when entering mouse mode
            listener.onControllerStateChanged(controllerNumber, 0, 0, 0, 0, 0, 0, 0)
            handler?.postDelayed(mouseMotionRunnable, 1000L / MOUSE_EMULATION_TICK_RATE_HZ)
        } else {
            releaseHeldMouseButtons()
            mouseMoveAccumX = 0f
            mouseMoveAccumY = 0f
            scrollAccumX = 0f
            scrollAccumY = 0f
            notifyState()
        }
        AppLogger.info("Gamepad mouse emulation toggled: $isMouseEmulationMode", TAG)
        listener.onGamepadMouseModeToggled(isMouseEmulationMode)
    }

    private fun releaseHeldMouseButtons() {
        if ((lastMouseInputMap and ControllerButtonFlags.A) != 0) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_LEFT, false)
        }
        if ((lastMouseInputMap and ControllerButtonFlags.B) != 0) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_RIGHT, false)
        }
        if ((lastMouseInputMap and ControllerButtonFlags.Y) != 0 ||
            (scrollStick == GamepadScrollStick.RIGHT && (lastMouseInputMap and ControllerButtonFlags.RS_CLK) != 0) ||
            (scrollStick == GamepadScrollStick.LEFT && (lastMouseInputMap and ControllerButtonFlags.LS_CLK) != 0)
        ) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_MIDDLE, false)
        }
        lastMouseInputMap = 0
    }

    private fun convertRawStickAxisToSpeedPxPerSec(stickX: Short, stickY: Short): Pair<Float, Float> {
        val fx = stickX.toFloat()
        val fy = stickY.toFloat()
        val mag = hypot(fx.toDouble(), fy.toDouble()).toFloat()
        val normalizedMag = min(1.0f, mag / RAW_STICK_AXIS_MAX)
        if (normalizedMag <= 0f || mag <= 0f) return 0f to 0f
        val targetSpeed = MOUSE_MOVE_BASE_SPEED_PX_PER_S * normalizedMag * normalizedMag * normalizedMag
        val scale = targetSpeed / mag
        return (fx * scale) to (fy * scale)
    }

    private fun sendEmulatedMouseMove(x: Short, y: Short, deltaTimeSec: Float) {
        val (vx, vy) = convertRawStickAxisToSpeedPxPerSec(x, y)
        val sensScale = mouseEmulationSensitivity / 100.0f
        mouseMoveAccumX += vx * deltaTimeSec * sensScale
        mouseMoveAccumY += vy * deltaTimeSec * sensScale
        val dx = mouseMoveAccumX.toInt().toShort()
        val dy = mouseMoveAccumY.toInt().toShort()
        if (dx.toInt() != 0 || dy.toInt() != 0) {
            listener.onEmulatedMouseMove(dx, (-dy).toShort())
            mouseMoveAccumX -= dx
            mouseMoveAccumY -= dy
        }
    }

    private fun sendEmulatedMouseScroll(x: Short, y: Short, deltaTimeSec: Float) {
        val (vx, vy) = convertRawStickAxisToSpeedPxPerSec(x, y)
        scrollAccumX += vx * deltaTimeSec
        scrollAccumY += vy * deltaTimeSec
        val sx = scrollAccumX.toInt().toShort()
        val sy = scrollAccumY.toInt().toShort()
        if (sy.toInt() != 0) {
            listener.onEmulatedMouseScroll(sy)
            scrollAccumY -= sy
        }
        if (sx.toInt() != 0) {
            listener.onEmulatedMouseHorizontalScroll(sx)
            scrollAccumX -= sx
        }
    }

    private fun handleMouseModeButtons(currentMap: Int) {
        val changedMask = currentMap xor lastMouseInputMap
        val aDown = (currentMap and ControllerButtonFlags.A) != 0
        val bDown = (currentMap and ControllerButtonFlags.B) != 0
        val yDown = (currentMap and ControllerButtonFlags.Y) != 0
        val rsClickDown = (currentMap and ControllerButtonFlags.RS_CLK) != 0
        val lsClickDown = (currentMap and ControllerButtonFlags.LS_CLK) != 0
        lastMouseInputMap = currentMap

        if ((changedMask and ControllerButtonFlags.A) != 0) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_LEFT, aDown)
        }
        if ((changedMask and ControllerButtonFlags.B) != 0) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_RIGHT, bDown)
        }
        if ((changedMask and ControllerButtonFlags.Y) != 0) {
            listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_MIDDLE, yDown)
        }
        when (scrollStick) {
            GamepadScrollStick.RIGHT -> {
                if ((changedMask and ControllerButtonFlags.RS_CLK) != 0) {
                    listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_MIDDLE, rsClickDown)
                }
            }
            GamepadScrollStick.LEFT -> {
                if ((changedMask and ControllerButtonFlags.LS_CLK) != 0) {
                    listener.onEmulatedMouseButton(MouseButtonCodes.BUTTON_MIDDLE, lsClickDown)
                }
            }
            GamepadScrollStick.NONE -> {}
        }

        if ((changedMask and ControllerButtonFlags.UP) != 0 && (currentMap and ControllerButtonFlags.UP) != 0) {
            listener.onEmulatedMouseScroll(120)
        }
        if ((changedMask and ControllerButtonFlags.DOWN) != 0 && (currentMap and ControllerButtonFlags.DOWN) != 0) {
            listener.onEmulatedMouseScroll(-120)
        }
        if ((changedMask and ControllerButtonFlags.RIGHT) != 0 && (currentMap and ControllerButtonFlags.RIGHT) != 0) {
            listener.onEmulatedMouseHorizontalScroll(120)
        }
        if ((changedMask and ControllerButtonFlags.LEFT) != 0 && (currentMap and ControllerButtonFlags.LEFT) != 0) {
            listener.onEmulatedMouseHorizontalScroll(-120)
        }
    }

    fun reset(keepMouseMode: Boolean = false) {
        handler?.removeCallbacks(startHoldRunnable)
        handler?.removeCallbacks(backHoldRunnable)
        releaseHeldMouseButtons()
        if (!keepMouseMode) {
            isMouseEmulationMode = false
            handler?.removeCallbacks(mouseMotionRunnable)
        }
        buttonFlags = 0
        hatFlags = 0
        touchpadClickDown = false
        emulatingButtonFlags = 0
        pendingExit = false
        leftTriggerPressed = false
        rightTriggerPressed = false
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
    }

    private fun applyRadialDeadzone(x: Float, y: Float, deadzoneRadius: Float): Pair<Float, Float> {
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()
        return if (magnitude <= deadzoneRadius) {
            0f to 0f
        } else {
            x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)
        }
    }

    fun tryHandleTouchpadEvent(event: MotionEvent): Boolean {
        val isTouchpadSource = event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        val isMouseSource = event.isFromSource(InputDevice.SOURCE_MOUSE)
        if (!isTouchpadSource && !isMouseSource) return false

        if (isMouseSource) {
            // Clickpad press reported as mouse event on some kernels/Shield
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchpadClickDown = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchpadClickDown = false
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    touchpadClickDown = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                }
                else -> return false
            }
            if (!isMouseEmulationMode) notifyState()
            return true
        }

        val xRange = touchpadXRange ?: event.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        val yRange = touchpadYRange ?: event.device?.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        val pRange = touchpadPressureRange ?: event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)

        val touchType: Byte = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MoonBridge.LI_TOUCH_EVENT_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    MoonBridge.LI_TOUCH_EVENT_CANCEL
                } else {
                    MoonBridge.LI_TOUCH_EVENT_UP
                }
            }
            MotionEvent.ACTION_MOVE -> MoonBridge.LI_TOUCH_EVENT_MOVE
            MotionEvent.ACTION_CANCEL -> MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                touchpadClickDown = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                if (!isMouseEmulationMode) notifyState()
                return true
            }
            else -> return false
        }

        if (touchType == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL) {
            listener.onControllerTouchpadEvent(controllerNumber, touchType, 0, 0f, 0f, 0f)
            return true
        }

        val indices = if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            0 until event.pointerCount
        } else {
            event.actionIndex..event.actionIndex
        }
        for (i in indices) {
            if (i !in 0 until event.pointerCount) continue
            val normX = if (xRange != null && xRange.range > 0f) {
                ((event.getX(i) - xRange.min) / xRange.range).coerceIn(0f, 1f)
            } else 0f
            val normY = if (yRange != null && yRange.range > 0f) {
                ((event.getY(i) - yRange.min) / yRange.range).coerceIn(0f, 1f)
            } else 0f
            val normP = if (pRange != null && pRange.range > 0f) {
                ((event.getPressure(i) - pRange.min) / pRange.range).coerceIn(0f, 1f)
            } else event.getPressure(i).coerceIn(0f, 1f)

            listener.onControllerTouchpadEvent(
                controllerNumber,
                touchType,
                event.getPointerId(i),
                normX,
                normY,
                normP
            )
        }
        return true
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (tryHandleTouchpadEvent(event)) return true
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        event.device?.let { dev ->
            // Re-sync axis configuration if handler was created without a device
            if (rightStickXAxis == MotionEvent.AXIS_Z &&
                getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z) == null &&
                getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX) != null
            ) {
                configureForDevice(dev)
            }
        }

        val deadzoneRadius = (stickDeadzonePercent.coerceIn(1, 50) / 100.0f)

        val rawLx = event.getAxisValue(leftStickXAxis)
        val rawLy = event.getAxisValue(leftStickYAxis)
        val (lx, ly) = applyRadialDeadzone(rawLx, rawLy, deadzoneRadius)

        val rawRx = event.getAxisValue(rightStickXAxis)
        val rawRy = event.getAxisValue(rightStickYAxis)
        val (rx, ry) = applyRadialDeadzone(rawRx, rawRy, deadzoneRadius)

        // Analog triggers with idle-negative normalization and deadzone
        var lt = event.getAxisValue(leftTriggerAxis)
        var rt = event.getAxisValue(rightTriggerAxis)
        if (!triggersIdleNegative && leftTriggerAxis == MotionEvent.AXIS_LTRIGGER && lt == 0f) {
            lt = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        }
        if (!triggersIdleNegative && rightTriggerAxis == MotionEvent.AXIS_RTRIGGER && rt == 0f) {
            rt = event.getAxisValue(MotionEvent.AXIS_GAS)
        }

        if (lt != 0f) leftTriggerAxisUsed = true
        if (rt != 0f) rightTriggerAxisUsed = true

        if (triggersIdleNegative) {
            lt = if (leftTriggerAxisUsed) (lt + 1.0f) / 2.0f else 0f
            rt = if (rightTriggerAxisUsed) (rt + 1.0f) / 2.0f else 0f
        }
        if (lt <= triggerDeadzone) lt = 0f
        if (rt <= triggerDeadzone) rt = 0f

        // D-Pad from Hat axes
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX != 0f) hatXAxisUsed = true
        if (hatY != 0f) hatYAxisUsed = true

        hatFlags = 0
        if (hatY < -0.5f) hatFlags = hatFlags or ControllerButtonFlags.UP
        if (hatY > 0.5f) hatFlags = hatFlags or ControllerButtonFlags.DOWN
        if (hatX < -0.5f) hatFlags = hatFlags or ControllerButtonFlags.LEFT
        if (hatX > 0.5f) hatFlags = hatFlags or ControllerButtonFlags.RIGHT

        leftTrigger = (lt.coerceIn(0f, 1f) * 255f).toInt().toByte()
        rightTrigger = (rt.coerceIn(0f, 1f) * 255f).toInt().toByte()

        leftStickX = (lx * 0x7FFE).toInt().toShort()
        leftStickY = (-ly * 0x7FFE).toInt().toShort() // Inverted Y for GameStream
        rightStickX = (rx * 0x7FFE).toInt().toShort()
        rightStickY = (-ry * 0x7FFE).toInt().toShort()

        if (isMouseEmulationMode) {
            handleMouseModeButtons(buttonFlags or hatFlags)
        } else {
            notifyState()
        }
        return true
    }

    fun handleButton(keyCode: Int, isDown: Boolean, repeatCount: Int = 0, scanCode: Int = 0): Boolean {
        val normalizedKey = if (keyCode == KeyEvent.KEYCODE_MENU) KeyEvent.KEYCODE_BUTTON_START else keyCode

        if (normalizedKey == KeyEvent.KEYCODE_BUTTON_SELECT) {
            hasSelect = true
        } else if (normalizedKey == KeyEvent.KEYCODE_BUTTON_MODE) {
            hasMode = true
        }

        // Check for hold-Start (750ms) to toggle mouse emulation
        if (normalizedKey == KeyEvent.KEYCODE_BUTTON_START && gamepadMouseEnabled) {
            if (isDown && repeatCount == 0) {
                startDownTime = System.currentTimeMillis()
                handler?.removeCallbacks(startHoldRunnable)
                handler?.postDelayed(startHoldRunnable, START_HOLD_MOUSE_MODE_MS)
            } else if (!isDown) {
                handler?.removeCallbacks(startHoldRunnable)
            }
        }

        // Check for hold-Select to open stream menu
        if (normalizedKey == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (isDown && repeatCount == 0) {
                selectHoldConsumed = false
                selectDownTime = System.currentTimeMillis()
                handler?.removeCallbacks(backHoldRunnable)
                handler?.postDelayed(backHoldRunnable, SELECT_HOLD_MENU_MS)
            } else if (!isDown) {
                handler?.removeCallbacks(backHoldRunnable)
                if (selectHoldConsumed) {
                    selectHoldConsumed = false
                    return true
                }
            }
        }

        when (normalizedKey) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (leftTriggerAxisUsed) return true
                leftTriggerPressed = isDown
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (rightTriggerAxisUsed) return true
                rightTriggerPressed = isDown
            }
            else -> {
                val buttonMask = mapKeyCodeToButton(normalizedKey, scanCode) ?: return false
                // Suppress duplicate D-pad key events if hat axis is active
                if (hatXAxisUsed && (buttonMask == ControllerButtonFlags.LEFT || buttonMask == ControllerButtonFlags.RIGHT)) {
                    return true
                }
                if (hatYAxisUsed && (buttonMask == ControllerButtonFlags.UP || buttonMask == ControllerButtonFlags.DOWN)) {
                    return true
                }
                buttonFlags = if (isDown) buttonFlags or buttonMask else buttonFlags and buttonMask.inv()
            }
        }

        // Handle button combo emulations (Quit combo, Start+Select Mode emulation, etc.)
        val combined = buttonFlags or hatFlags
        val quitCombo = ControllerButtonFlags.BACK or ControllerButtonFlags.START or
            ControllerButtonFlags.LB or ControllerButtonFlags.RB

        if (isDown) {
            if (combined == quitCombo) {
                pendingExit = true
                handler?.removeCallbacks(startHoldRunnable)
                handler?.removeCallbacks(backHoldRunnable)
            } else if (!hasMode && hasSelect && combined == (ControllerButtonFlags.START or ControllerButtonFlags.BACK)) {
                buttonFlags = (buttonFlags and (ControllerButtonFlags.START or ControllerButtonFlags.BACK).inv()) or
                    ControllerButtonFlags.SPECIAL
                emulatingButtonFlags = emulatingButtonFlags or EMULATING_SPECIAL
                handler?.removeCallbacks(startHoldRunnable)
                handler?.removeCallbacks(backHoldRunnable)
            } else if (needsClickpadEmulation && combined == (ControllerButtonFlags.BACK or ControllerButtonFlags.LB)) {
                buttonFlags = (buttonFlags and (ControllerButtonFlags.BACK or ControllerButtonFlags.LB).inv()) or
                    ControllerButtonFlags.TOUCHPAD
                emulatingButtonFlags = emulatingButtonFlags or EMULATING_TOUCHPAD
                handler?.removeCallbacks(backHoldRunnable)
            }
        } else {
            if ((emulatingButtonFlags and EMULATING_SPECIAL) != 0) {
                if ((buttonFlags and ControllerButtonFlags.START) == 0 ||
                    (buttonFlags and ControllerButtonFlags.BACK) == 0
                ) {
                    buttonFlags = buttonFlags and ControllerButtonFlags.SPECIAL.inv()
                    emulatingButtonFlags = emulatingButtonFlags and EMULATING_SPECIAL.inv()
                }
            }
            if ((emulatingButtonFlags and EMULATING_TOUCHPAD) != 0) {
                if ((buttonFlags and ControllerButtonFlags.BACK) == 0 ||
                    (buttonFlags and ControllerButtonFlags.LB) == 0
                ) {
                    buttonFlags = buttonFlags and ControllerButtonFlags.TOUCHPAD.inv()
                    emulatingButtonFlags = emulatingButtonFlags and EMULATING_TOUCHPAD.inv()
                }
            }
        }

        if (isMouseEmulationMode) {
            handleMouseModeButtons(buttonFlags or hatFlags)
        } else {
            notifyState()
        }

        if (!isDown && pendingExit && (buttonFlags or hatFlags) == 0) {
            pendingExit = false
            listener.onGamepadQuitRequested()
        }

        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        var keyCode = event.keyCode
        val scanCode = event.scanCode

        // Device-specific scancode remapping matching upstream ControllerHandler.handleRemapping
        if (usesLinuxGamepadStandardFaceButtons) {
            when (scanCode) {
                304 -> keyCode = KeyEvent.KEYCODE_BUTTON_A
                305 -> keyCode = KeyEvent.KEYCODE_BUTTON_B
                307 -> keyCode = KeyEvent.KEYCODE_BUTTON_Y
                308 -> keyCode = KeyEvent.KEYCODE_BUTTON_X
            }
        }
        if (isNonStandardDualShock4) {
            keyCode = when (scanCode) {
                304 -> KeyEvent.KEYCODE_BUTTON_X
                305 -> KeyEvent.KEYCODE_BUTTON_A
                306 -> KeyEvent.KEYCODE_BUTTON_B
                307 -> KeyEvent.KEYCODE_BUTTON_Y
                308 -> KeyEvent.KEYCODE_BUTTON_L1
                309 -> KeyEvent.KEYCODE_BUTTON_R1
                310, 311 -> return true // Suppress duplicate digital trigger press
                312 -> KeyEvent.KEYCODE_BUTTON_SELECT
                313 -> KeyEvent.KEYCODE_BUTTON_START
                314 -> KeyEvent.KEYCODE_BUTTON_THUMBL
                315 -> KeyEvent.KEYCODE_BUTTON_THUMBR
                316 -> KeyEvent.KEYCODE_BUTTON_MODE
                317 -> KeyEvent.KEYCODE_BUTTON_1
                else -> return true
            }
        }
        if (event.device?.vendorId == 0x054c && keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && scanCode == 317) {
            // DualShock 4 clickpad on NVIDIA Shield
            keyCode = KeyEvent.KEYCODE_BUTTON_1
        }
        if (event.device?.vendorId == 0x2dc8 && scanCode == 306) {
            // 8BitDo Mode button
            keyCode = KeyEvent.KEYCODE_BUTTON_MODE
        }
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && hasShare && scanCode == 167) {
            keyCode = KeyEvent.KEYCODE_MEDIA_RECORD
        }
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && !hatXAxisUsed && !hatYAxisUsed) {
            // Raw xpad D-Pad scancodes (704..707)
            when (scanCode) {
                704 -> keyCode = KeyEvent.KEYCODE_DPAD_LEFT
                705 -> keyCode = KeyEvent.KEYCODE_DPAD_RIGHT
                706 -> keyCode = KeyEvent.KEYCODE_DPAD_UP
                707 -> keyCode = KeyEvent.KEYCODE_DPAD_DOWN
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            keyCode = if (event.flags and KeyEvent.FLAG_FALLBACK != 0) {
                KeyEvent.KEYCODE_BUTTON_B
            } else {
                KeyEvent.KEYCODE_BUTTON_SELECT
            }
        }

        return handleButton(keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, scanCode)
    }

    private fun mapKeyCodeToButton(keyCode: Int, scanCode: Int = 0): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A -> if (swapFaceButtons) ControllerButtonFlags.B else ControllerButtonFlags.A
            KeyEvent.KEYCODE_BUTTON_B -> if (swapFaceButtons) ControllerButtonFlags.A else ControllerButtonFlags.B
            KeyEvent.KEYCODE_BUTTON_X -> if (swapFaceButtons) ControllerButtonFlags.Y else ControllerButtonFlags.X
            KeyEvent.KEYCODE_BUTTON_Y -> if (swapFaceButtons) ControllerButtonFlags.X else ControllerButtonFlags.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerButtonFlags.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerButtonFlags.RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerButtonFlags.LS_CLK
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerButtonFlags.RS_CLK
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> ControllerButtonFlags.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerButtonFlags.BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> ControllerButtonFlags.SPECIAL
            KeyEvent.KEYCODE_MEDIA_RECORD -> ControllerButtonFlags.MISC
            KeyEvent.KEYCODE_BUTTON_1 -> ControllerButtonFlags.TOUCHPAD
            KeyEvent.KEYCODE_DPAD_UP -> ControllerButtonFlags.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerButtonFlags.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerButtonFlags.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerButtonFlags.RIGHT
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> ControllerButtonFlags.UP or ControllerButtonFlags.LEFT
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> ControllerButtonFlags.UP or ControllerButtonFlags.RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> ControllerButtonFlags.DOWN or ControllerButtonFlags.LEFT
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> ControllerButtonFlags.DOWN or ControllerButtonFlags.RIGHT
            KeyEvent.KEYCODE_UNKNOWN -> {
                if (hasPaddles) {
                    when (scanCode) {
                        0x2c4 -> ControllerButtonFlags.PADDLE1
                        0x2c5 -> ControllerButtonFlags.PADDLE2
                        0x2c6 -> ControllerButtonFlags.PADDLE3
                        0x2c7 -> ControllerButtonFlags.PADDLE4
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }

    private fun notifyState() {
        var flags = buttonFlags or hatFlags
        if (touchpadClickDown) {
            flags = flags or ControllerButtonFlags.TOUCHPAD
        }
        listener.onControllerStateChanged(
            controllerNumber = controllerNumber,
            buttonFlags = flags,
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
