package com.moonlight.material.stream.video

/**
 * Paces video frames to match display refresh cycles.
 *
 * Ported from Sources/MoonlightMac/Pipeline/Video/FramePacer.swift.
 * Modes:
 * - Frame Pacing OFF (Opt-in disabled, default): Decoded frames are presented immediately with lowest latency.
 * - Frame Pacing ON (Opt-in enabled): Decoded frames are paced to match display refresh intervals,
 *   absorbing network jitter and eliminating micro-stutter on high refresh displays.
 */
class FramePacer(
    enabled: Boolean = false,
    refreshRate: Double = 60.0
) {
    private val lock = Any()

    var isPacingEnabled: Boolean = enabled
        private set

    var displayRefreshRate: Double = if (refreshRate > 0.0) refreshRate else 60.0
        private set

    private var onFrameCallback: ((Int) -> Unit)? = null
    private val frameQueue = ArrayDeque<Int>()
    private val maxQueuedFrames: Int = 2
    private var lastRenderTimeNs: Long = 0

    /**
     * Configure pacing behavior and optional callback.
     */
    fun configure(
        enabled: Boolean,
        refreshRate: Double = 60.0,
        onFrame: ((Int) -> Unit)? = null
    ) {
        synchronized(lock) {
            this.isPacingEnabled = enabled
            this.displayRefreshRate = if (refreshRate > 0.0) refreshRate else 60.0
            this.onFrameCallback = onFrame
            this.frameQueue.clear()
            this.lastRenderTimeNs = 0
        }
    }

    /**
     * Submit a buffer index to be presented.
     */
    fun submit(bufferIndex: Int) {
        val callback: ((Int) -> Unit)?
        val droppedIndex: Int?

        synchronized(lock) {
            if (!isPacingEnabled) {
                callback = onFrameCallback
                droppedIndex = null
            } else {
                frameQueue.addLast(bufferIndex)
                droppedIndex = if (frameQueue.size > maxQueuedFrames) {
                    frameQueue.removeFirst()
                } else null
                callback = null
            }
        }

        callback?.invoke(bufferIndex)
    }

    /**
     * Polls next paced frame if available.
     */
    fun pollNextFrame(): Int? {
        synchronized(lock) {
            if (!isPacingEnabled || frameQueue.isEmpty()) return null
            return frameQueue.removeFirst()
        }
    }

    /**
     * Calculates the target render timestamp in nanoseconds for releaseOutputBuffer(index, timestampNs).
     */
    fun calculateReleaseTimeNs(): Long {
        synchronized(lock) {
            val nowNs = System.nanoTime()
            if (!isPacingEnabled) {
                return nowNs
            }

            val framePeriodNs = (1_000_000_000.0 / displayRefreshRate).toLong()
            if (lastRenderTimeNs == 0L || nowNs > lastRenderTimeNs + (framePeriodNs * 2)) {
                lastRenderTimeNs = nowNs
                return nowNs
            }

            val targetNs = lastRenderTimeNs + framePeriodNs
            lastRenderTimeNs = if (targetNs < nowNs) nowNs else targetNs
            return lastRenderTimeNs
        }
    }

    /**
     * Stops pacer and clears pending frames.
     */
    fun stop() {
        synchronized(lock) {
            isPacingEnabled = false
            frameQueue.clear()
            onFrameCallback = null
            lastRenderTimeNs = 0
        }
    }
}
