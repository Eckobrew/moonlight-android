package com.moonlight.material.stream.video

/** Thread-safe, monotonic, rolling measurements across input, decoder and render callbacks. */
class VideoPerformanceTracker(private val nowNs: () -> Long = System::nanoTime) {
    data class Snapshot(val receivedFps: Double, val decodedFps: Double, val renderedFps: Double,
        val decodeTimeMs: Double, val networkDroppedFrames: Int, val decoderDroppedFrames: Int)
    private val received = ArrayDeque<Long>()
    private val decoded = ArrayDeque<Long>()
    private val rendered = ArrayDeque<Long>()
    private val decodeTimes = ArrayDeque<Pair<Long, Double>>()
    private val queued = LinkedHashMap<Long, Long>()
    private var startedNs = nowNs()
    private var lastFrame: Int? = null
    private var networkDrops = 0
    private var decoderDrops = 0
    private val windowNs = 1_000_000_000L

    @Synchronized fun reset() {
        received.clear(); decoded.clear(); rendered.clear(); decodeTimes.clear(); queued.clear()
        startedNs = nowNs(); lastFrame = null; networkDrops = 0; decoderDrops = 0
    }
    @Synchronized fun receivedFrame(number: Int) {
        if (number == lastFrame) return
        lastFrame?.let { if (number > it + 1) networkDrops += number - it - 1 }
        lastFrame = number
        received.addLast(nowNs())
    }
    @Synchronized fun queuedFrame(presentationTimeUs: Long) {
        queued[presentationTimeUs] = nowNs()
        if (queued.size > 512) queued.remove(queued.keys.first())
    }
    @Synchronized fun decodedFrame(presentationTimeUs: Long): Double? {
        val time = nowNs()
        decoded.addLast(time)
        val start = queued.remove(presentationTimeUs) ?: return null
        val elapsed = ((time - start) / 1_000_000.0).coerceAtLeast(0.0)
        decodeTimes.addLast(time to elapsed)
        return elapsed
    }
    @Synchronized fun renderedFrame(renderTimeNs: Long) {
        if (renderTimeNs > 0) rendered.addLast(renderTimeNs)
    }
    @Synchronized fun droppedDecoderFrame() { decoderDrops++ }
    @Synchronized fun snapshot(): Snapshot {
        val time = nowNs()
        val cutoff = time - windowNs
        fun fps(events: ArrayDeque<Long>): Double {
            while (events.isNotEmpty() && events.first() <= cutoff) events.removeFirst()
            val elapsed = (time - startedNs).coerceIn(1L, windowNs)
            return events.count { it <= time } * 1_000_000_000.0 / elapsed
        }
        while (decodeTimes.isNotEmpty() && decodeTimes.first().first <= cutoff) decodeTimes.removeFirst()
        return Snapshot(fps(received), fps(decoded), fps(rendered),
            if (decodeTimes.isEmpty()) 0.0 else decodeTimes.sumOf { it.second } / decodeTimes.size,
            networkDrops, decoderDrops)
    }
}
