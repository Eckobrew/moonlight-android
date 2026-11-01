package com.moonlight.material.audio

import com.moonlight.material.stream.audio.AudioRingBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    private fun writeRamp(ring: AudioRingBuffer, start: Int = 0, frames: Int) {
        val samples = FloatArray(frames * ring.channels)
        var idx = 0
        for (frame in start until (start + frames)) {
            for (ch in 0 until ring.channels) {
                samples[idx++] = (frame * ring.channels + ch).toFloat()
            }
        }
        ring.write(samples, 0, frames)
    }

    private fun read(ring: AudioRingBuffer, frames: Int): Array<FloatArray>? {
        val planes = Array(ring.channels) { FloatArray(frames) { Float.NaN } }
        val success = ring.read(planes, frames)
        return if (success) planes else null
    }

    @Test
    fun fullAudioBufferDoesNotAppearEmpty() {
        val ring = AudioRingBuffer(channels = 2, capacity = 2048)
        writeRamp(ring, frames = 2048)
        val samples = read(ring, frames = 512)
        assertNotNull(samples)

        val expectedLeft = FloatArray(512) { frame -> (frame * 2).toFloat() }
        val expectedRight = FloatArray(512) { frame -> (frame * 2 + 1).toFloat() }
        assertArrayEquals(expectedLeft, samples!![0], 0.0001f)
        assertArrayEquals(expectedRight, samples[1], 0.0001f)
    }

    @Test
    fun audioWrapPreservesUnreadSamples() {
        val ring = AudioRingBuffer(channels = 1, capacity = 2048)
        writeRamp(ring, frames = 1536)
        assertNotNull(read(ring, frames = 1024))

        writeRamp(ring, start = 1536, frames = 1536)
        val samples = read(ring, frames = 1024)
        assertNotNull(samples)

        val expected = FloatArray(1024) { frame -> (1024 + frame).toFloat() }
        assertArrayEquals(expected, samples!![0], 0.0001f)
    }

    @Test
    fun audioOverflowRetainsNewestCushion() {
        val ring = AudioRingBuffer(channels = 1)
        writeRamp(ring, frames = 48000)
        val samples = read(ring, frames = 512)
        assertNotNull(samples)

        val expected = FloatArray(512) { frame -> (46560 + frame).toFloat() }
        assertArrayEquals(expected, samples!![0], 0.0001f)
    }

    @Test
    fun audioUnderrunFadesAndRebuffers() {
        val ring = AudioRingBuffer(channels = 1)
        writeRamp(ring, frames = 1200)
        assertNotNull(read(ring, frames = 512))

        val tail = read(ring, frames = 1024)
        assertNotNull(tail)
        // Sample at 687 was the last real sample (1199.0)
        assertEquals(1199.0f, tail!![0][687], 0.0001f)
        // Last sample was faded to 0
        assertEquals(0.0f, tail[0][1023], 0.0001f)

        // After underrun, re-enters buffering mode
        writeRamp(ring, frames = 480)
        assertTrue(read(ring, frames = 512) == null)

        writeRamp(ring, start = 480, frames = 720)
        assertTrue(read(ring, frames = 512) != null)

        ring.reset()
        assertTrue(read(ring, frames = 512) == null)
    }

    @Test
    fun audioLargeRenderQuantaDoNotStarve() {
        for (quantum in intArrayOf(128, 512, 1024, 2048, 4096, 8192)) {
            val ring = AudioRingBuffer(channels = 1)
            var elapsed = 0
            var renders = 0

            for (packet in 0 until 1000) {
                writeRamp(ring, start = packet * 240, frames = 240)
                elapsed += 240
                while (elapsed >= quantum) {
                    if (read(ring, frames = quantum) != null) {
                        renders++
                    }
                    elapsed -= quantum
                }
            }

            assertTrue("Audio must leave prebuffering for $quantum-frame callbacks (renders: $renders)", renders > 10)

            ring.reset()
            assertTrue(read(ring, frames = quantum) == null)

            writeRamp(ring, frames = quantum + 960)
            assertTrue(read(ring, frames = quantum) != null)
        }
    }
}
