package com.moonlight.material.stream.input

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

interface MouseEventListener {
    fun onMouseMoveRelative(deltaX: Short, deltaY: Short)
    fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short)
    fun onMouseButton(button: Byte, isDown: Boolean)
    fun onMouseScroll(amount: Short)
}

class TouchMouseHandler(
    private val listener: MouseEventListener,
    var isDesktopMouseMode: Boolean = false,
    var mouseSensitivityX: Double = 1.0,
    var mouseSensitivityY: Double = 1.0,
    var swapMouseButtons: Boolean = false,
    var reverseScroll: Boolean = false
) {
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var pointerCount = 0
    private var accumulatedX = 0.0
    private var accumulatedY = 0.0

    fun handleTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                accumulatedX = 0.0
                accumulatedY = 0.0
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    val rawDeltaX = (event.x - lastX) * mouseSensitivityX
                    val rawDeltaY = (event.y - lastY) * mouseSensitivityY

                    accumulatedX += rawDeltaX
                    accumulatedY += rawDeltaY

                    val intDeltaX = accumulatedX.toInt()
                    val intDeltaY = accumulatedY.toInt()

                    if (intDeltaX != 0 || intDeltaY != 0) {
                        accumulatedX -= intDeltaX
                        accumulatedY -= intDeltaY
                        listener.onMouseMoveRelative(intDeltaX.toShort(), intDeltaY.toShort())
                    }

                    lastX = event.x
                    lastY = event.y
                } else if (pointerCount == 2) {
                    val dy = (event.y - lastY)
                    val scrollDir = if (reverseScroll) -1 else 1
                    if (abs(dy) > 10) {
                        listener.onMouseScroll((dy * scrollDir / 5).toInt().toShort())
                        lastY = event.y
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - downTime
                val distance = hypot((event.x - lastX).toDouble(), (event.y - lastY).toDouble())

                // Quick tap detection for clicks
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
                        // Two finger tap = right click
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
            val deltaX = (event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * mouseSensitivityX).toInt().toShort()
            val deltaY = (event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * mouseSensitivityY).toInt().toShort()
            if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                listener.onMouseMoveRelative(deltaX, deltaY)
            }

            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                val scrollDir = if (reverseScroll) -1 else 1
                listener.onMouseScroll((vScroll * 120 * scrollDir).toInt().toShort())
            }
            return true
        }
        return false
    }
}
