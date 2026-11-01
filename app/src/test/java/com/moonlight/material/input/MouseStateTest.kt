package com.moonlight.material.input

import com.moonlight.material.stream.input.MouseWheelMotion
import com.moonlight.material.stream.input.RelativeMouseMotion
import com.moonlight.material.stream.input.StreamMouseFocus
import org.junit.Assert.assertEquals
import org.junit.Test

class MouseStateTest {

    @Test
    fun fullscreenStartupAndBannerFocusCanRecover() {
        val state = StreamMouseFocus(windowKey = false)
        assertEquals(StreamMouseFocus.Action.RESTORE_KEY_WINDOW, state.action)

        state.windowKey = true
        assertEquals(StreamMouseFocus.Action.CAPTURE, state.action)

        state.windowKey = false // Temporary system interruption
        assertEquals(StreamMouseFocus.Action.RESTORE_KEY_WINDOW, state.action)

        state.windowKey = true
        assertEquals(StreamMouseFocus.Action.CAPTURE, state.action)
    }

    @Test
    fun recoveryDoesNotOverrideUserFocusOrReleasedCapture() {
        val base = StreamMouseFocus(windowKey = false)
        val testCases = listOf(
            base.copy(appActive = false),
            base.copy(otherKeyWindow = true),
            base.copy(onActiveSpace = false),
            base.copy(blockedByDialog = true),
            base.copy(minimized = true),
            base.copy(visible = false),
            base.copy(requested = false)
        )

        for (state in testCases) {
            assertEquals(StreamMouseFocus.Action.RELEASE, state.action)
        }
    }

    @Test
    fun unitMouseSensitivityMatchesSDLCocoaTruncation() {
        val motion = RelativeMouseMotion()
        for (i in 0 until 10) {
            val (dx, dy) = motion.consume(x = 1.9, y = -1.9, scaleX = 1.0, scaleY = 1.0, accumulateSensitivity = true)
            assertEquals(1.toShort(), dx)
            assertEquals((-1).toShort(), dy)
        }

        val (microX, microY) = motion.consume(x = 0.9, y = -0.9, scaleX = 1.0, scaleY = 1.0, accumulateSensitivity = true)
        assertEquals(0.toShort(), microX)
        assertEquals(0.toShort(), microY)
    }

    @Test
    fun customSensitivityKeepsFractionsAndResetClearsThem() {
        val motion = RelativeMouseMotion()

        val first = motion.consume(x = 1.0, y = -1.0, scaleX = 0.5, scaleY = 0.5, accumulateSensitivity = true)
        assertEquals(0.toShort(), first.first)
        assertEquals(0.toShort(), first.second)

        val second = motion.consume(x = 1.0, y = -1.0, scaleX = 0.5, scaleY = 0.5, accumulateSensitivity = true)
        assertEquals(1.toShort(), second.first)
        assertEquals((-1).toShort(), second.second)

        motion.consume(x = 1.0, y = 1.0, scaleX = 0.5, scaleY = 0.5, accumulateSensitivity = true)
        motion.reset()

        val afterReset = motion.consume(x = 1.0, y = 1.0, scaleX = 0.5, scaleY = 0.5, accumulateSensitivity = true)
        assertEquals(0.toShort(), afterReset.first)
        assertEquals(0.toShort(), afterReset.second)

        val unaccumulated = motion.consume(x = -1.9, y = 1.9, scaleX = 0.5, scaleY = 0.5, accumulateSensitivity = false)
        assertEquals(0.toShort(), unaccumulated.first)
        assertEquals(0.toShort(), unaccumulated.second)
    }

    @Test
    fun scrollingMatchesSDLCocoaAxesTicksAndClamp() {
        val precise = MouseWheelMotion.consume(x = 0.125, y = -0.125, precise = true)
        assertEquals((-15).toShort(), precise.first)
        assertEquals((-15).toShort(), precise.second)

        val discrete = MouseWheelMotion.consume(x = -0.125, y = 0.125, precise = false)
        assertEquals(120.toShort(), discrete.first)
        assertEquals(120.toShort(), discrete.second)

        val accelerated = MouseWheelMotion.consume(x = 10.0, y = -10.0, precise = true)
        assertEquals((-120).toShort(), accelerated.first)
        assertEquals((-120).toShort(), accelerated.second)
    }
}
