package com.moonlight.material.video

import com.moonlight.material.stream.video.VideoPerformanceTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPerformanceTrackerTest {
    @Test fun renderedFpsIsSeparateFromDecoderAndNetworkRates() {
        var time = 0L
        val tracker = VideoPerformanceTracker { time }
        for (frame in 1..120) {
            time = frame * 1_000_000_000L / 120
            tracker.receivedFrame(frame)
            tracker.decodedFrame(frame.toLong()) // Unmatched PTS must still count as an output.
            if (frame % 2 == 0) tracker.renderedFrame(time)
        }
        val snapshot = tracker.snapshot()
        assertEquals(120.0, snapshot.receivedFps, 0.01)
        assertEquals(120.0, snapshot.decodedFps, 0.01)
        assertEquals(60.0, snapshot.renderedFps, 0.01)
    }
    @Test fun decodeLatencyUsesQueueTimeRatherThanPresentationClock() {
        var time = 100_000_000L
        val tracker = VideoPerformanceTracker { time }
        tracker.queuedFrame(99999999999L)
        time += 10_500_000L
        tracker.decodedFrame(99999999999L)
        assertEquals(10.5, tracker.snapshot().decodeTimeMs, 0.001)
    }
    @Test fun stalledStreamFallsToZeroAndDropsRemainDistinct() {
        var time = 0L
        val tracker = VideoPerformanceTracker { time }
        time = 100_000_000L
        tracker.receivedFrame(1)
        tracker.receivedFrame(5)
        tracker.receivedFrame(5)
        tracker.droppedDecoderFrame()
        tracker.decodedFrame(5)
        tracker.renderedFrame(time)
        time += 1_100_000_000L
        val snapshot = tracker.snapshot()
        assertEquals(0.0, snapshot.renderedFps, 0.0)
        assertEquals(0.0, snapshot.decodedFps, 0.0)
        assertEquals(0.0, snapshot.receivedFps, 0.0)
        assertEquals(3, snapshot.networkDroppedFrames)
        assertEquals(1, snapshot.decoderDroppedFrames)
    }
}
