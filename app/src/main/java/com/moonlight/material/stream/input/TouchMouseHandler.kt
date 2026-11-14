package com.moonlight.material.stream.input

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

interface MouseEventListener {
    fun onNativeTouch(
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
        major: Float,
        minor: Float,
        rotation: Short = -1
    ): Int = -5501

    fun onNativePen(
        type: Byte,
        tool: Byte,
        buttons: Byte,
        x: Float,
        y: Float,
        pressure: Float,
        major: Float,
        minor: Float,
        rotation: Short = -1,
        tilt: Byte
    ): Int = -5501

    fun onMouseMoveRelative(deltaX: Short, deltaY: Short)
    fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short)
    fun onMouseButton(button: Byte, isDown: Boolean)
    fun onMouseScroll(amount: Short)
    fun onMouseHorizontalScroll(amount: Short) {}
}

/**
 * Handles touch gestures, virtual trackpad, stylus/pen, and physical mouse input.
 *
 * Aligned with upstream moonlight-android (Game.java, AbsoluteTouchContext.java,
 * RelativeTouchContext.java, AndroidNativePointerCaptureProvider.java):
 * - Native multi-touch passthrough (LiSendTouchEvent) with orientation-adjusted contact area & rotation
 * - Fallback absolute touch with 100ms delayed tap release, long-press right-click, and tap-and-drag
 * - Relative trackpad with historical motion samples, 100ms tap release, 650ms hold-to-drag,
 *   double-tap-and-drag, seamless multi-finger pointer transitions, and smooth high-res 2-finger scroll
 * - Stylus/pen native forwarding plus mouse fallback (eraser right-click, barrel buttons, deadzones)
 * - Physical mouse historical sample summation (`event.historySize`) and high-res scroll wheel
 */
class TouchMouseHandler(
    private val listener: MouseEventListener,
    var isDesktopMouseMode: Boolean = false,
    var mouseSensitivityX: Double = 1.0,
    var mouseSensitivityY: Double = 1.0,
    var mouseSubPixelAccumulation: Boolean = true,
    var swapMouseButtons: Boolean = false,
    var reverseScroll: Boolean = false
) {
    private var nativeTouchSupported = true
    private var nativeTouchActive = false
    private var nativePenSupported = true
    private var nativePenActive = false

    private val handler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    private val relativeMotion = RelativeMouseMotion()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var maxPointerCountInGesture = 0
    private var confirmedMove = false
    private var confirmedDrag = false
    private var confirmedScroll = false
    private var lastTapUp = -1L
    private var lastAbsTapUpTime = 0L
    private var lastAbsTapUpX = 0f
    private var lastAbsTapUpY = 0f
    private var dragButton: Byte? = null
    private var absTapConfirmed = false
    private var absRightClickConfirmed = false

    private val physicalMotion = RelativeMouseMotion()
    private var physicalButtons = 0

    // Delayed button release runnables (100ms hold so polling games register taps)
    private val releaseLeftButtonRunnable = Runnable {
        val btn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
        listener.onMouseButton(btn, false)
    }
    private val releaseRightButtonRunnable = Runnable {
        val btn = if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
        listener.onMouseButton(btn, false)
    }

    // Hold-in-place drag / long-press right-click timer (650ms)
    private val longPressRunnable = Runnable {
        if (confirmedMove || confirmedScroll || maxPointerCountInGesture > 1) return@Runnable
        if (isDesktopMouseMode) {
            // Relative touchpad mode: 650ms stationary hold engages drag
            val btn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
            confirmedDrag = true
            dragButton = btn
            listener.onMouseButton(btn, true)
        } else {
            // Absolute fallback mode: 650ms stationary hold switches to right click
            if (absTapConfirmed) {
                val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                listener.onMouseButton(leftBtn, false)
                absTapConfirmed = false
            }
            absRightClickConfirmed = true
            val rightBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
            listener.onMouseButton(rightBtn, true)
        }
    }

    // Absolute mode 100ms tap confirmation timer
    private val absTapConfirmRunnable = Runnable {
        if (!isDesktopMouseMode && !absTapConfirmed && !absRightClickConfirmed && maxPointerCountInGesture == 1) {
            absTapConfirmed = true
            val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
            listener.onMouseButton(leftBtn, true)
        }
    }

    private fun cancelTimers() {
        handler?.removeCallbacks(longPressRunnable)
        handler?.removeCallbacks(absTapConfirmRunnable)
    }

    private fun scheduleTapClick(isRightClick: Boolean) {
        val btn = if (isRightClick) {
            if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
        } else {
            if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
        }
        val releaseRunnable = if (isRightClick) releaseRightButtonRunnable else releaseLeftButtonRunnable
        handler?.removeCallbacks(releaseRunnable)
        listener.onMouseButton(btn, true)
        val h = handler
        if (h != null) {
            h.postDelayed(releaseRunnable, TAP_BUTTON_HOLD_MS)
        } else {
            listener.onMouseButton(btn, false)
        }
    }

    private fun endDrag(): Boolean {
        val button = dragButton ?: return false
        dragButton = null
        confirmedDrag = false
        listener.onMouseButton(button, false)
        return true
    }

    fun releasePhysicalMouse() {
        updatePhysicalButtons(0)
        physicalMotion.reset()
    }

    private fun updatePhysicalButtons(buttons: Int) {
        val changed = physicalButtons xor buttons
        physicalButtons = buttons
        for ((mask, code) in PHYSICAL_BUTTONS) {
            if (changed and mask == 0) continue
            val mapped = when {
                swapMouseButtons && code == MouseButtonCodes.BUTTON_LEFT -> MouseButtonCodes.BUTTON_RIGHT
                swapMouseButtons && code == MouseButtonCodes.BUTTON_RIGHT -> MouseButtonCodes.BUTTON_LEFT
                else -> code
            }
            listener.onMouseButton(mapped, buttons and mask != 0)
        }
    }

    companion object {
        private const val TAP_BUTTON_HOLD_MS = 100L
        private const val LONG_PRESS_THRESHOLD_MS = 650L
        private const val ABS_DOWN_DEADZONE_MS = 100L
        private const val TAP_MOVEMENT_THRESHOLD_PX = 20f
        private const val DOUBLE_TAP_DEADZONE_MS = 250L
        private const val DOUBLE_TAP_DEADZONE_PX = 60f
        private const val SCROLL_SPEED_FACTOR = 5f

        private val PHYSICAL_BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to MouseButtonCodes.BUTTON_LEFT,
            MotionEvent.BUTTON_SECONDARY to MouseButtonCodes.BUTTON_RIGHT,
            MotionEvent.BUTTON_TERTIARY to MouseButtonCodes.BUTTON_MIDDLE,
            MotionEvent.BUTTON_BACK to MouseButtonCodes.BUTTON_X1,
            MotionEvent.BUTTON_FORWARD to MouseButtonCodes.BUTTON_X2,
            MotionEvent.BUTTON_STYLUS_PRIMARY to MouseButtonCodes.BUTTON_RIGHT,
            MotionEvent.BUTTON_STYLUS_SECONDARY to MouseButtonCodes.BUTTON_MIDDLE
        )
    }

    fun reset() {
        cancelTimers()
        handler?.removeCallbacks(releaseLeftButtonRunnable)
        handler?.removeCallbacks(releaseRightButtonRunnable)
        releasePhysicalMouse()
        endDrag()
        if (absTapConfirmed) {
            val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
            listener.onMouseButton(leftBtn, false)
            absTapConfirmed = false
        }
        if (absRightClickConfirmed) {
            val rightBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
            listener.onMouseButton(rightBtn, false)
            absRightClickConfirmed = false
        }
        lastTapUp = -1L
        if (nativePenActive) listener.onNativePen(7, 0, 0, 0f, 0f, 0f, 0f, 0f, -1, -1)
        nativePenActive = false
        nativePenSupported = true
        if (nativeTouchActive) listener.onNativeTouch(7, 0, 0f, 0f, 0f, 0f, 0f, -1)
        nativeTouchActive = false
        nativeTouchSupported = true
        relativeMotion.reset()
        activePointerId = MotionEvent.INVALID_POINTER_ID
        lastX = 0f
        lastY = 0f
    }

    private fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
        val device = event.device ?: return -1
        if (device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) == null) return -1
        var deg = Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).roundToInt()
        if (deg < 0) deg += 360
        return deg.coerceIn(0, 359).toShort()
    }

    private fun getNormalizedContactArea(
        event: MotionEvent,
        pointerIndex: Int,
        viewWidth: Int,
        viewHeight: Int,
        isHover: Boolean
    ): Pair<Float, Float> {
        val major = if (isHover) event.getToolMajor(pointerIndex) else event.getTouchMajor(pointerIndex)
        val minor = if (isHover) event.getToolMinor(pointerIndex) else event.getTouchMinor(pointerIndex)
        if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f

        val orientation = if (event.device?.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) != null) {
            event.getOrientation(pointerIndex).toDouble()
        } else {
            0.0
        }
        val majorX = major * cos(orientation)
        val majorY = major * sin(orientation)
        val minorX = minor * -sin(orientation)
        val minorY = minor * cos(orientation)

        val normMajor = hypot(majorX / viewWidth, majorY / viewHeight).toFloat()
        val normMinor = hypot(minorX / viewWidth, minorY / viewHeight).toFloat()
        return normMajor to normMinor
    }

    private fun handlePenEvent(event: MotionEvent, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL && nativePenActive) {
            nativePenActive = false
            listener.onNativePen(7, 0, 0, 0f, 0f, 0f, 0f, 0f, -1, -1)
            return true
        }
        val index = if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            (0 until event.pointerCount).firstOrNull {
                event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            } ?: return false
        } else event.actionIndex
        if (index !in 0 until event.pointerCount) return false
        val tool: Byte = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS -> 1
            MotionEvent.TOOL_TYPE_ERASER -> 2
            else -> return false
        }
        val type: Byte = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> 0
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) 4 else 2
            MotionEvent.ACTION_MOVE -> 3
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> 5
            MotionEvent.ACTION_HOVER_EXIT -> 6
            else -> return false
        }

        if (nativePenSupported && !isDesktopMouseMode) {
            val buttons = (if (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) 1 else 0) or
                (if (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) 2 else 0)
            val tilt = if (event.device?.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null) {
                Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, index).toDouble()).roundToInt().coerceIn(0, 90).toByte()
            } else (-1).toByte()
            val isHover = type == 0.toByte() || type == 6.toByte()
            val pressure = if (isHover) {
                val range = event.device?.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
                if (range != null && range.range > 0f) {
                    (event.getAxisValue(MotionEvent.AXIS_DISTANCE, index) - range.min) / range.range
                } else 0f
            } else event.getPressure(index)
            val (normMajor, normMinor) = getNormalizedContactArea(event, index, width, height, isHover)
            val rotation = getRotationDegrees(event, index)

            val result = listener.onNativePen(
                type,
                tool,
                buttons.toByte(),
                (event.getX(index) / width).coerceIn(0f, 1f),
                (event.getY(index) / height).coerceIn(0f, 1f),
                pressure.coerceIn(0f, 1f),
                normMajor,
                normMinor,
                rotation,
                tilt
            )
            if (result != -5501) {
                if (result == 0) nativePenActive = when (type.toInt()) { 0, 1, 3, 5 -> true; else -> false }
                return true
            }
            nativePenSupported = false
        }

        // Stylus mouse fallback when host doesn't support native pen events
        if (!isDesktopMouseMode) {
            val x = event.getX(index).toInt().coerceIn(0, width).toShort()
            val y = event.getY(index).toInt().coerceIn(0, height).toShort()
            listener.onMouseMoveAbsolute(x, y, width.toShort(), height.toShort())
            updatePhysicalButtons(event.buttonState)
            val stylusBtn = if (tool == 2.toByte()) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> listener.onMouseButton(stylusBtn, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> listener.onMouseButton(stylusBtn, false)
            }
            return true
        }
        return false
    }

    fun handleTouchEvent(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Boolean {
        if (handlePenEvent(event, viewWidth, viewHeight)) return true

        // 1. Native multi-touch passthrough in Touchscreen mode (preserving user commit 02b3d2dc)
        if (!isDesktopMouseMode && nativeTouchSupported &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && viewWidth > 0 && viewHeight > 0
        ) {
            val type: Byte = when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> 0
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 1
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) 4 else 2
                MotionEvent.ACTION_MOVE -> 3
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> 5
                MotionEvent.ACTION_HOVER_EXIT -> 6
                MotionEvent.ACTION_CANCEL -> 7
                else -> return false
            }
            val indices = if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                0 until event.pointerCount
            } else {
                event.actionIndex..event.actionIndex
            }
            var result = 0
            if (type.toInt() == 7) {
                result = listener.onNativeTouch(type, 0, 0f, 0f, 0f, 0f, 0f, -1)
            } else {
                val isHover = type.toInt() == 0 || type.toInt() == 6
                for (i in indices) {
                    if (i !in 0 until event.pointerCount) continue
                    val (normMajor, normMinor) = getNormalizedContactArea(event, i, viewWidth, viewHeight, isHover)
                    val rotation = getRotationDegrees(event, i)
                    val px = ((event.getX(i) + offsetX) / viewWidth).coerceIn(0f, 1f)
                    val py = ((event.getY(i) + offsetY) / viewHeight).coerceIn(0f, 1f)
                    result = listener.onNativeTouch(
                        type,
                        event.getPointerId(i),
                        px,
                        py,
                        event.getPressure(i).coerceIn(0f, 1f),
                        normMajor,
                        normMinor,
                        rotation
                    )
                    if (result == -5501) break
                }
            }
            if (result != -5501) {
                nativeTouchActive = event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL
                return true
            }
            nativeTouchSupported = false
        }

        // 2. Relative Trackpad Mode or Fallback Absolute Touch Mode
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTimers()
                activePointerId = event.getPointerId(0)
                val x = event.x + offsetX
                val y = event.y + offsetY
                lastX = x
                lastY = y
                downTime = event.eventTime
                downX = x
                downY = y
                maxPointerCountInGesture = 1
                confirmedMove = false
                confirmedScroll = false
                absTapConfirmed = false
                absRightClickConfirmed = false
                relativeMotion.reset()

                if (isDesktopMouseMode) {
                    if (lastTapUp >= 0 && downTime - lastTapUp <= ViewConfiguration.getDoubleTapTimeout()) {
                        val button = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                        confirmedDrag = true
                        dragButton = button
                        listener.onMouseButton(button, true)
                    } else {
                        handler?.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
                    }
                    lastTapUp = -1L
                } else if (viewWidth > 0 && viewHeight > 0) {
                    // Fallback absolute touch mode: check double-tap spatial deadzone
                    val inDoubleTapDeadzone = (downTime - lastAbsTapUpTime <= DOUBLE_TAP_DEADZONE_MS) &&
                        hypot((x - lastAbsTapUpX).toDouble(), (y - lastAbsTapUpY).toDouble()) <= DOUBLE_TAP_DEADZONE_PX
                    if (!inDoubleTapDeadzone) {
                        listener.onMouseMoveAbsolute(
                            x.toInt().coerceIn(0, viewWidth).toShort(),
                            y.toInt().coerceIn(0, viewHeight).toShort(),
                            viewWidth.toShort(),
                            viewHeight.toShort()
                        )
                    }
                    handler?.postDelayed(absTapConfirmRunnable, ABS_DOWN_DEADZONE_MS)
                    handler?.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelTimers()
                endDrag()
                if (absTapConfirmed) {
                    val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                    listener.onMouseButton(leftBtn, false)
                    absTapConfirmed = false
                }
                maxPointerCountInGesture = maxOf(maxPointerCountInGesture, event.pointerCount)
                val idx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                lastX = event.getX(idx) + offsetX
                lastY = event.getY(idx) + offsetY
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val primaryIdx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                val currX = event.getX(primaryIdx) + offsetX
                val currY = event.getY(primaryIdx) + offsetY
                val distFromDown = hypot((currX - downX).toDouble(), (currY - downY).toDouble()).toFloat()

                if (distFromDown > TAP_MOVEMENT_THRESHOLD_PX) {
                    confirmedMove = true
                    cancelTimers()
                }

                if (event.pointerCount == 1) {
                    if (isDesktopMouseMode) {
                        // Process historical motion samples for smooth relative cursor motion
                        for (h in 0 until event.historySize) {
                            val hx = event.getHistoricalX(primaryIdx, h) + offsetX
                            val hy = event.getHistoricalY(primaryIdx, h) + offsetY
                            val (hdx, hdy) = relativeMotion.consume(
                                x = (hx - lastX).toDouble(),
                                y = (hy - lastY).toDouble(),
                                scaleX = mouseSensitivityX,
                                scaleY = mouseSensitivityY,
                                accumulateSensitivity = mouseSubPixelAccumulation
                            )
                            if (hdx.toInt() != 0 || hdy.toInt() != 0) {
                                listener.onMouseMoveRelative(hdx, hdy)
                            }
                            lastX = hx
                            lastY = hy
                        }

                        val dx = (currX - lastX).toDouble()
                        val dy = (currY - lastY).toDouble()
                        val (deltaX, deltaY) = relativeMotion.consume(
                            x = dx,
                            y = dy,
                            scaleX = mouseSensitivityX,
                            scaleY = mouseSensitivityY,
                            accumulateSensitivity = mouseSubPixelAccumulation
                        )
                        if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                            listener.onMouseMoveRelative(deltaX, deltaY)
                        }
                    } else if (viewWidth > 0 && viewHeight > 0) {
                        // Fallback absolute mode: confirm tap-and-drag once moved beyond deadzone
                        if (confirmedMove && !absTapConfirmed && !absRightClickConfirmed && maxPointerCountInGesture == 1) {
                            absTapConfirmed = true
                            val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                            listener.onMouseButton(leftBtn, true)
                        }
                        if (confirmedMove || absTapConfirmed) {
                            listener.onMouseMoveAbsolute(
                                currX.toInt().coerceIn(0, viewWidth).toShort(),
                                currY.toInt().coerceIn(0, viewHeight).toShort(),
                                viewWidth.toShort(),
                                viewHeight.toShort()
                            )
                        }
                    }
                    lastX = currX
                    lastY = currY
                } else if (event.pointerCount >= 2) {
                    // Smooth high-resolution 2-finger vertical scrolling
                    val dy = currY - lastY
                    val scrollDir = if (reverseScroll) -1f else 1f
                    val highResScroll = (dy * SCROLL_SPEED_FACTOR * scrollDir).roundToInt().toShort()
                    if (highResScroll.toInt() != 0) {
                        confirmedScroll = true
                        listener.onMouseScroll(highResScroll)
                        lastX = currX
                        lastY = currY
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                if (liftedId == activePointerId) {
                    // Transition smoothly to remaining finger so cursor doesn't jump
                    val newIndex = if (liftedIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastX = event.getX(newIndex) + offsetX
                        lastY = event.getY(newIndex) + offsetY
                    }
                }
                if (event.pointerCount == 2 && maxPointerCountInGesture == 2 && !confirmedMove && !confirmedScroll) {
                    val elapsed = event.eventTime - downTime
                    if (elapsed < 250) {
                        scheduleTapClick(isRightClick = true)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelTimers()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (endDrag()) return true

                if (!isDesktopMouseMode) {
                    if (absRightClickConfirmed) {
                        val rightBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
                        listener.onMouseButton(rightBtn, false)
                        absRightClickConfirmed = false
                        return true
                    }
                    if (absTapConfirmed) {
                        val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                        listener.onMouseButton(leftBtn, false)
                        absTapConfirmed = false
                        return true
                    }
                }

                val elapsed = event.eventTime - downTime
                val x = event.x + offsetX
                val y = event.y + offsetY
                val distance = hypot((x - downX).toDouble(), (y - downY).toDouble())

                // Quick single-finger tap: 100ms held click so polling games detect it
                if (maxPointerCountInGesture == 1 && !confirmedMove && elapsed < 250 && distance < TAP_MOVEMENT_THRESHOLD_PX) {
                    scheduleTapClick(isRightClick = false)
                    if (isDesktopMouseMode) {
                        lastTapUp = event.eventTime
                    } else {
                        lastAbsTapUpTime = event.eventTime
                        lastAbsTapUpX = x
                        lastAbsTapUpY = y
                    }
                }
                return true
            }
        }
        return false
    }

    fun handleGenericMotion(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        if (handlePenEvent(event, viewWidth, viewHeight)) return true
        val isRelativeSource = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            (event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.actionMasked == MotionEvent.ACTION_MOVE)
        if (!isRelativeSource && !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            releasePhysicalMouse()
            return true
        }

        if (isRelativeSource && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val useRelAxes = !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.device?.getMotionRange(MotionEvent.AXIS_RELATIVE_X, event.source) != null
            val axisX = if (useRelAxes) MotionEvent.AXIS_RELATIVE_X else MotionEvent.AXIS_X
            val axisY = if (useRelAxes) MotionEvent.AXIS_RELATIVE_Y else MotionEvent.AXIS_Y

            // Sum all historical motion samples so high-polling-rate mice don't lose batched deltas
            var totalRawX = 0.0
            var totalRawY = 0.0
            for (h in 0 until event.historySize) {
                totalRawX += event.getHistoricalAxisValue(axisX, h).toDouble()
                totalRawY += event.getHistoricalAxisValue(axisY, h).toDouble()
            }
            totalRawX += event.getAxisValue(axisX).toDouble()
            totalRawY += event.getAxisValue(axisY).toDouble()

            val (dx, dy) = physicalMotion.consume(
                totalRawX,
                totalRawY,
                mouseSensitivityX,
                mouseSensitivityY,
                mouseSubPixelAccumulation
            )
            if (dx.toInt() != 0 || dy.toInt() != 0) {
                listener.onMouseMoveRelative(dx, dy)
            }
        } else if (!isRelativeSource &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) &&
            viewWidth > 0 && viewHeight > 0
        ) {
            listener.onMouseMoveAbsolute(
                event.x.toInt().coerceIn(0, viewWidth).toShort(),
                event.y.toInt().coerceIn(0, viewHeight).toShort(),
                viewWidth.toShort(),
                viewHeight.toShort()
            )
        }

        // Samsung DeX mouse sends primary clicks as ACTION_DOWN / ACTION_UP with buttonState == 0
        var effectiveButtons = event.buttonState
        if (event.actionMasked == MotionEvent.ACTION_DOWN && effectiveButtons == 0) {
            effectiveButtons = MotionEvent.BUTTON_PRIMARY
        }
        updatePhysicalButtons(effectiveButtons)

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val direction = if (reverseScroll) -1f else 1f
            val vScroll = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * direction * 120f).roundToInt().toShort()
            val hScroll = (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * direction * 120f).roundToInt().toShort()
            if (hScroll.toInt() != 0) listener.onMouseHorizontalScroll(hScroll)
            if (vScroll.toInt() != 0) listener.onMouseScroll(vScroll)
        }
        return true
    }
}
