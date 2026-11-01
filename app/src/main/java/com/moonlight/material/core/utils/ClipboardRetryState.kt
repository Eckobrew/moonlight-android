package com.moonlight.material.core.utils

import com.moonlight.material.networking.HostControlError
import kotlin.math.min
import kotlin.math.pow

data class ClipboardRetryState(
    val failures: Int = 0,
    val retryAtEpochMs: Long = 0L,
    val unsupported: Boolean = false
) {
    fun canAttempt(nowEpochMs: Long): Boolean = !unsupported && nowEpochMs >= retryAtEpochMs

    fun succeeded(): ClipboardRetryState = ClipboardRetryState()

    fun failed(error: Throwable, nowEpochMs: Long): ClipboardRetryState {
        val isUnsupported = (error as? HostControlError)?.let { it.code in listOf(404, 405, 501) } ?: false
        val newFailures = min(failures + 1, 6)
        val delaySec = min(30.0, 2.0.pow((newFailures - 1).toDouble()))
        return ClipboardRetryState(
            failures = newFailures,
            retryAtEpochMs = nowEpochMs + (delaySec * 1000).toLong(),
            unsupported = isUnsupported
        )
    }
}
