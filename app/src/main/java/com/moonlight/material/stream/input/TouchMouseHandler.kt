package com.moonlight.material.stream.input

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

interface MouseEventListener {
    fun onMouseMoveRelative(deltaX: Short, deltaY: Short)
    fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short)
    fun onMouseButton(button: Byte, isDown: Boolean)
    fun onMouseScroll(amount: Short)
}

/**
 * Handles touch gestures, virtual trackpad, and physical mouse input.
 *
 * Implements mouse motion scaling, sub-pixel sensitivity accumulation,
 * and mouse wheel expansion matching StreamMouseState.
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
    private val relativeMotion = RelativeMouseMotion()
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var pointerCount = 0

    fun reset() {
        relativeMotion.reset()
        lastX = 0f
        lastY = 0f
    }

    fun handleTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                relativeMotion.reset()

                if (!isDesktopMouseMode && viewWidth > 0 && viewHeight > 0) {
                    listener.onMouseMoveAbsolute(
                        event.x.toInt().toShort(),
                        event.y.toInt().toShort(),
                        viewWidth.toShort(),
                        viewHeight.toShort()
                    )
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    if (isDesktopMouseMode) {
                        // Trackpad / relative mode
                        val dx = (event.x - lastX).toDouble()
                        val dy = (event.y - lastY).toDouble()
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
                        // Touchscreen / absolute mode
                        listener.onMouseMoveAbsolute(
                            event.x.toInt().toShort(),
                            event.y.toInt().toShort(),
                            viewWidth.toShort(),
                            viewHeight.toShort()
                        )
                    }
                    lastX = event.x
                    lastY = event.y
                } else if (pointerCount == 2) {
                    // Two-finger scrolling
                    val dy = (event.y - lastY).toDouble()
                    val scrollDir = if (reverseScroll) -1.0 else 1.0
                    val (_, wheelY) = MouseWheelMotion.consume(
                        x = 0.0,
                        y = (dy / 20.0) * scrollDir,
                        precise = false
                    )
                    if (wheelY.toInt() != 0) {
                        listener.onMouseScroll(wheelY)
                        lastY = event.y
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - downTime
                val distance = hypot((event.x - lastX).toDouble(), (event.y - lastY).toDouble())

                // Quick tap detection: primary click
                if (elapsed < 250 && distance < 20) {
                    val leftBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_RIGHT else MouseButtonCodes.BUTTON_LEFT
                    listener.onMouseButton(leftBtn, true)
                    listener.onMouseButton(leftBtn, false)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 2) {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (elapsed < 250) {
                        // Two finger tap: secondary (right) click
                        val rightBtn = if (swapMouseButtons) MouseButtonCodes.BUTTON_LEFT else MouseButtonCodes.BUTTON_RIGHT
                        listener.onMouseButton(rightBtn, true)
                        listener.onMouseButton(rightBtn, false)
                    }
                }
                return true
            }
        }
        return false
    }

    fun handleGenericMotion(event: MotionEvent): Boolean {
        // Physical Mouse via Bluetooth or USB
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val rawDx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).toDouble()
            val rawDy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).toDouble()

            val (deltaX, deltaY) = relativeMotion.consume(
                x = rawDx,
                y = rawDy,
                scaleX = mouseSensitivityX,
                scaleY = mouseSensitivityY,
                accumulateSensitivity = mouseSubPixelAccumulation
            )

            if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                listener.onMouseMoveRelative(deltaX, deltaY)
            }

            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
            if (vScroll != 0.0) {
                val scrollDir = if (reverseScroll) -1.0 else 1.0
                val (_, wheelY) = MouseWheelMotion.consume(
                    x = 0.0,
                    y = vScroll * scrollDir,
                    precise = false
                )
                if (wheelY.toInt() != 0) {
                    listener.onMouseScroll(wheelY)
                }
            }
            return true
        }
        return false
    }
}
