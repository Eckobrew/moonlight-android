package com.moonlight.material.video

import com.moonlight.material.stream.video.FramePacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FramePacerTest {

    @Test
    fun stoppedPacerDoesNotPresentFrames() {
        val pacer = FramePacer()
        val counter = AtomicInteger(0)

        // Configure with pacing OFF (immediate callback)
        pacer.configure(enabled = false, refreshRate = 60.0) { _ ->
            counter.incrementAndGet()
        }

        pacer.submit(1)
        assertEquals(1, counter.get())

        // Stop pacer: submits should no longer invoke callback
        pacer.stop()
        pacer.submit(2)
        assertEquals(1, counter.get())

        // Reconfigure and submit again
        pacer.configure(enabled = false, refreshRate = 60.0) { _ ->
            counter.incrementAndGet()
        }
        pacer.submit(3)
        assertEquals(2, counter.get())
    }

    @Test
    fun pacingEnabledQueuesAndLimitsQueueSize() {
        val pacer = FramePacer()

        pacer.configure(enabled = true, refreshRate = 60.0)

        pacer.submit(10)
        pacer.submit(20)

        // Poll frames
        assertEquals(10, pacer.pollNextFrame())
        assertEquals(20, pacer.pollNextFrame())
        assertNull(pacer.pollNextFrame())

        // Exceed max queue size of 2: oldest buffer should be dropped
        pacer.submit(1)
        pacer.submit(2)
        pacer.submit(3) // drops 1

        assertEquals(2, pacer.pollNextFrame())
        assertEquals(3, pacer.pollNextFrame())
        assertNull(pacer.pollNextFrame())
    }

    @Test
    fun calculateReleaseTimeNsPacing() {
        val pacer = FramePacer()
        pacer.configure(enabled = false, refreshRate = 60.0)

        // In unpaced mode, release time is close to current system nano time
        val t0 = System.nanoTime()
        val releaseUnpaced = pacer.calculateReleaseTimeNs()
        val t1 = System.nanoTime()
        assertTrue(releaseUnpaced in t0..t1)

        // In paced mode at 60 Hz (~16.6ms per frame = 16_666_666 ns)
        pacer.configure(enabled = true, refreshRate = 60.0)
        val paced1 = pacer.calculateReleaseTimeNs()
        val paced2 = pacer.calculateReleaseTimeNs()
        assertTrue("Second paced frame timestamp must be scheduled ahead", paced2 >= paced1)
    }
}
