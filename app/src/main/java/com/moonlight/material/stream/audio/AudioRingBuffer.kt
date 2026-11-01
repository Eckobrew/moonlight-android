package com.moonlight.material.stream.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Planar float ring buffer shared between audio decode/ingest and audio presentation.
 *
 * Ported directly from Sources/MoonlightMac/Pipeline/Audio/AudioPlaybackEngine.swift:AudioRingBuffer.
 * Features:
 * - Dynamic prebuffer threshold scaling with sample rate and render quantum.
 * - Overflow retention ceiling keeping newest packet cushion.
 * - Underrun smooth fading to zero to prevent clicks/pops.
 * - Rebuffering accumulation after underrun.
 */
class AudioRingBuffer(
    val channels: Int,
    val capacity: Int = 48000,
    val sampleRate: Double = 48000.0
) {
    init {
        require(channels > 0 && capacity > 0 && sampleRate > 0.0)
    }

    private val storage = FloatArray(channels * capacity)
    private var writeHead: Int = 0
    private var readHead: Int = 0
    private var bufferedFrames: Int = 0
    private var isBuffering: Boolean = true
    private var renderQuantumFrames: Int = 0
    private val lock = Any()

    private fun planeOffset(channel: Int): Int = channel * capacity

    fun reset() {
        synchronized(lock) {
            writeHead = 0
            readHead = 0
            bufferedFrames = 0
            isBuffering = true
        }
    }

    private val prebufferFrames: Int
        get() = min(capacity, max(renderQuantumFrames + (sampleRate * 0.010).toInt(), (sampleRate * 0.020).toInt()))

    fun write(interleaved: FloatArray, offset: Int = 0, frames: Int) {
        if (frames <= 0) return

        synchronized(lock) {
            val framesToWrite = min(frames, capacity)
            val sourceStart = offset + (frames - framesToWrite) * channels
            val firstChunk = min(framesToWrite, capacity - writeHead)
            val secondChunk = framesToWrite - firstChunk

            for (ch in 0 until channels) {
                val planeBase = planeOffset(ch)

                if (secondChunk == 0) {
                    var src = sourceStart + ch
                    var dst = planeBase + writeHead
                    for (i in 0 until framesToWrite) {
                        storage[dst] = interleaved[src]
                        src += channels
                        dst++
                    }
                } else {
                    var src = sourceStart + ch
                    var dst = planeBase + writeHead
                    for (i in 0 until firstChunk) {
                        storage[dst] = interleaved[src]
                        src += channels
                        dst++
                    }
                    dst = planeBase
                    for (i in 0 until secondChunk) {
                        storage[dst] = interleaved[src]
                        src += channels
                        dst++
                    }
                }
            }

            writeHead += framesToWrite
            if (writeHead >= capacity) writeHead -= capacity

            bufferedFrames = min(capacity, bufferedFrames + framesToWrite)
            val prebuffer = prebufferFrames
            val maxAllowed = min(capacity, max((sampleRate * 0.080).toInt(), prebuffer + (sampleRate * 0.010).toInt()))
            val targetCushion = min(capacity, max((sampleRate * 0.030).toInt(), prebuffer))

            if (bufferedFrames > maxAllowed) {
                bufferedFrames = targetCushion
            }
            readHead = (writeHead - bufferedFrames + capacity) % capacity
        }
    }

    fun read(destPlanes: Array<FloatArray>, frames: Int): Boolean {
        if (frames <= 0) return false

        synchronized(lock) {
            renderQuantumFrames = min(frames, capacity)
            val available = bufferedFrames

            if (isBuffering) {
                val minPrebuffer = prebufferFrames
                if (available < minPrebuffer) {
                    return false
                }
                isBuffering = false
            } else if (available == 0) {
                isBuffering = true
                return false
            }

            val framesToRead = min(available, frames)
            val firstChunk = min(framesToRead, capacity - readHead)
            val secondChunk = framesToRead - firstChunk

            for (ch in 0 until min(channels, destPlanes.size)) {
                val dest = destPlanes[ch]
                val planeBase = planeOffset(ch)

                System.arraycopy(storage, planeBase + readHead, dest, 0, firstChunk)
                if (secondChunk > 0) {
                    System.arraycopy(storage, planeBase, dest, firstChunk, secondChunk)
                }

                if (framesToRead < frames) {
                    // Smoothly fade the last sample to zero across the underrun tail
                    val lastSample = if (framesToRead > 0) dest[framesToRead - 1] else 0.0f
                    val remaining = frames - framesToRead
                    for (i in 0 until remaining) {
                        val scale = (remaining - i - 1).toFloat() / remaining.toFloat()
                        dest[framesToRead + i] = lastSample * scale
                    }
                }
            }

            if (framesToRead < frames) {
                isBuffering = true
            }

            bufferedFrames -= framesToRead
            readHead += framesToRead
            if (readHead >= capacity) readHead -= capacity
            return true
        }
    }
}
