package com.moonlight.material.stream.input

import kotlin.math.max
import kotlin.math.min

/**
 * Focus and capture states for stream mouse input.
 *
 * Ported from Sources/MoonlightMac/Input/StreamMouseState.swift.
 */
data class StreamMouseFocus(
    var requested: Boolean = true,
    var appActive: Boolean = true,
    var windowKey: Boolean = true,
    var otherKeyWindow: Boolean = false,
    var visible: Boolean = true,
    var onActiveSpace: Boolean = true,
    var blockedByDialog: Boolean = false,
    var minimized: Boolean = false
) {
    enum class Action { RELEASE, CAPTURE, RESTORE_KEY_WINDOW }

    val action: Action
        get() {
            if (!requested || !appActive || !visible || !onActiveSpace ||
                blockedByDialog || minimized || otherKeyWindow) {
                return Action.RELEASE
            }
            return if (windowKey) Action.CAPTURE else Action.RESTORE_KEY_WINDOW
        }
}

/**
 * Matches SDL2 Cocoa delta conversion before optional user sensitivity scaling.
 * Truncates toward zero and accumulates sub-pixel remainders when enabled.
 */
class RelativeMouseMotion {
    private var remainderX: Double = 0.0
    private var remainderY: Double = 0.0

    fun reset() {
        remainderX = 0.0
        remainderY = 0.0
    }

    fun consume(
        x: Double,
        y: Double,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
        accumulateSensitivity: Boolean = true
    ): Pair<Short, Short> {
        if (!x.isFinite() || !y.isFinite() || !scaleX.isFinite() || !scaleY.isFinite()) {
            return Pair(0, 0)
        }

        val roundTowardZeroX = if (x >= 0.0) kotlin.math.floor(x) else kotlin.math.ceil(x)
        val roundTowardZeroY = if (y >= 0.0) kotlin.math.floor(y) else kotlin.math.ceil(y)

        val dx = roundTowardZeroX * scaleX + (if (accumulateSensitivity) remainderX else 0.0)
        val dy = roundTowardZeroY * scaleY + (if (accumulateSensitivity) remainderY else 0.0)

        if (!dx.isFinite() || !dy.isFinite()) {
            reset()
            return Pair(0, 0)
        }

        val integralX = if (dx >= 0.0) kotlin.math.floor(dx) else kotlin.math.ceil(dx)
        val integralY = if (dy >= 0.0) kotlin.math.floor(dy) else kotlin.math.ceil(dy)

        remainderX = if (accumulateSensitivity) dx - integralX else 0.0
        remainderY = if (accumulateSensitivity) dy - integralY else 0.0

        val clampedX = max(Short.MIN_VALUE.toDouble(), min(Short.MAX_VALUE.toDouble(), integralX)).toInt().toShort()
        val clampedY = max(Short.MIN_VALUE.toDouble(), min(Short.MAX_VALUE.toDouble(), integralY)).toInt().toShort()

        return Pair(clampedX, clampedY)
    }
}

/**
 * Matches SDL Cocoa mouse wheel conversion: flips X, preserves Y, and expands discrete
 * wheel movements to full ticks (120 units) clamped to [-120, 120].
 */
object MouseWheelMotion {
    fun consume(x: Double, y: Double, precise: Boolean): Pair<Short, Short> {
        if (!x.isFinite() || !y.isFinite()) return Pair(0, 0)

        fun wheel(delta: Double): Short {
            val value = if (precise) delta else {
                if (delta >= 0.0) kotlin.math.ceil(delta) else kotlin.math.floor(delta)
            }
            val clamped = max(-1.0, min(1.0, value))
            return (clamped * 120.0).toInt().toShort()
        }

        return Pair(wheel(-x), wheel(y))
    }
}
