package com.moonlight.material

import com.moonlight.material.core.utils.HostAppShutdown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class HostAppShutdownTest {

    private class ShutdownProbe {
        private val started = CompletableDeferred<Unit>()
        var cancelled = false
            private set

        suspend fun waitForStart() = started.await()
        fun markStarted() { started.complete(Unit) }
        fun markCancelled() { cancelled = true }
    }

    @Test
    fun shutdownDeadlineCancelsOutstandingWork() {
        runBlocking {
            val probe = ShutdownProbe()
            HostAppShutdown.run(
                timeoutMs = 20L,
                sleep = { duration ->
                    probe.waitForStart()
                }
            ) {
                probe.markStarted()
                try {
                    delay(60_000L)
                } catch (e: CancellationException) {
                    probe.markCancelled()
                }
            }
            assertTrue(probe.cancelled)
        }
    }

    @Test
    fun shutdownDoesNotWaitForDeadlineWhenRequestCompletes() {
        runBlocking {
            val deadline = ShutdownProbe()
            HostAppShutdown.run(
                sleep = { _ ->
                    deadline.markStarted()
                    try {
                        delay(60_000L)
                    } catch (e: CancellationException) {
                        deadline.markCancelled()
                    }
                }
            ) {
                deadline.waitForStart()
            }
            assertTrue(deadline.cancelled)
        }
    }
}
