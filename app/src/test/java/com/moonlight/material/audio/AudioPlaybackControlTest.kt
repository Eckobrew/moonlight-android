package com.moonlight.material.audio

import com.moonlight.material.stream.audio.AudioOutputBackend
import com.moonlight.material.stream.audio.AudioPlaybackControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackControlTest {

    private class FakeAudioOutput : AudioOutputBackend {
        var running = false
        var isMuted = false
            private set
        var startCount = 0
        var disposed = false
        var failures = 0
        val startsMuted = mutableListOf<Boolean>()
        private var callback: (() -> Unit)? = null

        override val isRunning: Boolean get() = running

        fun failNextStarts(count: Int) { failures = count }
        fun setCallback(cb: () -> Unit) { callback = cb }
        fun notifyChange() { callback?.invoke() }

        override fun setMuted(muted: Boolean) {
            this.isMuted = muted
        }

        override fun start() {
            startCount++
            startsMuted.add(isMuted)
            if (failures > 0) {
                failures--
                throw RuntimeException("Simulated start failure")
            }
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun dispose() {
            running = false
            disposed = true
        }
    }

    @Test
    fun backgroundMuteSurvivesSetupAndEngineReplacement() {
        val control = AudioPlaybackControl()
        deferCleanup(control)

        // Configure while app is not focused with muteOnFocusLoss=true
        control.configure(muteOnFocusLoss = true, appFocused = false)

        val first = FakeAudioOutput()
        assertTrue(control.install { cb -> first.setCallback(cb); first })
        control.start()
        assertEquals(listOf(true), first.startsMuted)

        control.cleanup()

        // Replace engine: new engine must also start muted because app is still unfocused
        val second = FakeAudioOutput()
        assertTrue(control.install { cb -> second.setCallback(cb); second })
        control.start()
        assertEquals(listOf(true), second.startsMuted)

        // Focus app: unmute
        control.updateFocus(true)
        control.synchronize()
        assertFalse(second.isMuted)

        // Unfocus app: mute
        control.updateFocus(false)
        control.synchronize()
        assertTrue(second.isMuted)
    }

    @Test
    fun stoppedAudioCancelsPendingRecovery() {
        val control = AudioPlaybackControl()
        deferCleanup(control)

        val output = FakeAudioOutput()
        output.failNextStarts(1)

        assertTrue(control.install { cb -> output.setCallback(cb); output })
        try {
            control.start()
        } catch (ignored: Exception) {}

        control.stop()
        output.notifyChange()
        control.synchronize()

        assertFalse(output.isRunning)
    }

    @Test
    fun oldConfigurationCallbacksCannotRestartReplacedOutput() {
        val control = AudioPlaybackControl()
        deferCleanup(control)

        val old = FakeAudioOutput()
        assertTrue(control.install { cb -> old.setCallback(cb); old })
        control.start()

        val replacement = FakeAudioOutput()
        assertTrue(control.install { cb -> replacement.setCallback(cb); replacement })
        control.start()

        // Stale callback from old backend
        old.notifyChange()
        control.synchronize()

        assertTrue(old.disposed)
        assertFalse(old.isRunning)
        assertEquals(1, old.startCount)
        assertEquals(1, replacement.startCount)
    }

    private fun deferCleanup(control: AudioPlaybackControl) {
        Runtime.getRuntime().addShutdownHook(Thread { control.cleanup() })
    }
}
