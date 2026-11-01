package com.moonlight.material.core.utils

import com.moonlight.material.core.models.Host
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Both app termination and stream stop use the same cancellable deadline.
 * Matches HostAppShutdown.swift:4-28
 */
object HostAppShutdown {

    suspend fun run(
        timeoutMs: Long = 2000L,
        sleep: suspend (Long) -> Unit = { delay(it) },
        operation: suspend () -> Unit
    ) = coroutineScope {
        val opJob = launch { operation() }
        val sleepJob = launch {
            try {
                sleep(timeoutMs)
                opJob.cancel()
            } catch (e: CancellationException) {
                // Cancelled when operation completed early
            }
        }
        opJob.invokeOnCompletion {
            sleepJob.cancel()
        }
        opJob.join()
    }

    suspend fun quit(
        host: Host,
        httpClient: SunshineHTTPClient,
        expectedAppID: Int? = null,
        timeoutMs: Long = 2000L
    ) {
        run(timeoutMs) {
            if (expectedAppID != null) {
                val (current, _) = try {
                    httpClient.fetchServerInfo(host)
                } catch (e: Exception) {
                    return@run
                }
                if (current.currentAppId != expectedAppID) {
                    return@run
                }
            }
            try {
                httpClient.quitApp(host)
            } catch (e: Exception) {
                // Ignore errors during quit
            }
        }
    }
}
