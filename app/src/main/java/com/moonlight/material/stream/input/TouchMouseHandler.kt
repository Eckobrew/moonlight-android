package com.moonlight.material.stream.input

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

interface MouseEventListener {
    fun onNativeTouch(eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float, major: Float, minor: Float): Int = -5501
    fun onMouseMoveRelative(deltaX: Short, deltaY: Short)
    fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short)
    fun onMouseButton(button: Byte, isDown: Boolean)
    fun onMouseScroll(amount: Short)
    fun onMouseHorizontalScroll(amount: Short) {}
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
    private var nativeTouchSupported = true
    private var nativeTouchActive = false
    private val relativeMotion = RelativeMouseMotion()
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false
    private var pointerCount = 0
    private val physicalMotion = RelativeMouseMotion()
    private var physicalButtons = 0

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
        private val PHYSICAL_BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to MouseButtonCodes.BUTTON_LEFT,
            MotionEvent.BUTTON_SECONDARY to MouseButtonCodes.BUTTON_RIGHT,
            MotionEvent.BUTTON_TERTIARY to MouseButtonCodes.BUTTON_MIDDLE,
            MotionEvent.BUTTON_BACK to MouseButtonCodes.BUTTON_X1,
            MotionEvent.BUTTON_FORWARD to MouseButtonCodes.BUTTON_X2
        )
    }

    fun reset() {
        releasePhysicalMouse()
        if (nativeTouchActive) listener.onNativeTouch(7, 0, 0f, 0f, 0f, 0f, 0f)
        nativeTouchActive = false
        nativeTouchSupported = true
        relativeMotion.reset()
        lastX = 0f
        lastY = 0f
    }

    fun handleTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        if (!isDesktopMouseMode && nativeTouchSupported &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && viewWidth > 0 && viewHeight > 0) {
            val type: Byte = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 1
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    if (android.os.Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) 4 else 2
                MotionEvent.ACTION_MOVE -> 3
                MotionEvent.ACTION_CANCEL -> 7
                else -> return false
            }
            val indices = if (event.actionMasked == MotionEvent.ACTION_MOVE) 0 until event.pointerCount
                else event.actionIndex..event.actionIndex
            var result = 0
            if (type.toInt() == 7) {
                result = listener.onNativeTouch(type, 0, 0f, 0f, 0f, 0f, 0f)
            } else {
                for (i in indices) {
                    result = listener.onNativeTouch(type, event.getPointerId(i),
                        (event.getX(i) / viewWidth).coerceIn(0f, 1f),
                        (event.getY(i) / viewHeight).coerceIn(0f, 1f),
                        event.getPressure(i).coerceIn(0f, 1f),
                        event.getTouchMajor(i) / maxOf(viewWidth, viewHeight),
                        event.getTouchMinor(i) / maxOf(viewWidth, viewHeight))
                    if (result == -5501) break
                }
            }
            if (result != -5501) {
                nativeTouchActive = event.actionMasked != MotionEvent.ACTION_UP && type.toInt() != 7
                return true
            }
            nativeTouchSupported = false
        }
        pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                multiTouch = false
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

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                reset()
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
                val distance = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())

                // Quick tap detection: primary click
                if (!multiTouch && elapsed < 250 && distance < 20) {
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

    fun handleGenericMotion(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        if (!relative && !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            releasePhysicalMouse()
            return true
        }

        if (relative && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val (dx, dy) = physicalMotion.consume(
                event.x.toDouble(), event.y.toDouble(),
                mouseSensitivityX, mouseSensitivityY, mouseSubPixelAccumulation
            )
            if (dx.toInt() != 0 || dy.toInt() != 0) listener.onMouseMoveRelative(dx, dy)
        } else if (!relative && viewWidth > 0 && viewHeight > 0) {
            listener.onMouseMoveAbsolute(
                event.x.toInt().coerceIn(0, viewWidth).toShort(),
                event.y.toInt().coerceIn(0, viewHeight).toShort(),
                viewWidth.toShort(), viewHeight.toShort()
            )
        }

        updatePhysicalButtons(event.buttonState)
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val direction = if (reverseScroll) -1 else 1
            val (x, y) = MouseWheelMotion.consume(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble() * direction,
                event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble() * direction,
                precise = false
            )
            if (x.toInt() != 0) listener.onMouseHorizontalScroll(x)
            if (y.toInt() != 0) listener.onMouseScroll(y)
        }
        return true
    }
}
