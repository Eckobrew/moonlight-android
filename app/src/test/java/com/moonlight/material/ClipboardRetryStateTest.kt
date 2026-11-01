package com.moonlight.material

import com.moonlight.material.core.utils.ClipboardRetryState
import com.moonlight.material.networking.HostControlError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ClipboardRetryStateTest {

    @Test
    fun backoffIncreasesWithConsecutiveFailures() {
        var state = ClipboardRetryState()
        val now = 100000L

        assertTrue(state.canAttempt(now))
        assertEquals(0, state.failures)

        state = state.failed(IOException("Network error"), now)
        assertEquals(1, state.failures)
        assertFalse(state.unsupported)
        assertEquals(now + 1000L, state.retryAtEpochMs)
        assertFalse(state.canAttempt(now))
        assertTrue(state.canAttempt(now + 1001L))

        state = state.failed(IOException("Network error"), now + 1000L)
        assertEquals(2, state.failures)
        assertEquals(now + 1000L + 2000L, state.retryAtEpochMs)

        state = state.succeeded()
        assertEquals(0, state.failures)
        assertEquals(0L, state.retryAtEpochMs)
        assertFalse(state.unsupported)
    }

    @Test
    fun unsupportedEndpointFlagsPermanently() {
        var state = ClipboardRetryState()
        val now = 100000L

        state = state.failed(HostControlError(404, "Not Found"), now)
        assertTrue(state.unsupported)
        assertFalse(state.canAttempt(now + 9999999L))
    }
}
