package com.moonlight.material.networking

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.security.HostTrustException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException

sealed class HostWakeFailure(message: String) : Exception(message) {
    object MissingMAC : HostWakeFailure(
        "This PC is unreachable and has no saved MAC address. Wake it manually, then connect once to save its MAC address."
    )
    object TimedOut : HostWakeFailure(
        "The PC did not respond after waking. Check that Wake-on-LAN is enabled on the PC and supported by the network."
    )
}

/**
 * Resolves host connectivity, initiating Wake-on-LAN if host is sleeping.
 * Waits for live serverinfo rather than simply a port opening.
 * Matches HostWakeConnection.swift:5-62
 */
object HostWakeConnection {
    suspend fun resolve(
        host: Host,
        attempts: Int = 30,
        probe: suspend (Host) -> Host,
        wake: suspend (String) -> Unit = { WakeOnLAN.wake(it) },
        pause: suspend () -> Unit = { delay(2000) },
        onWaking: () -> Unit = {}
    ): Host {
        currentCoroutineContext().ensureActive()
        try {
            val current = probe(host)
            if (current.isOnline || current.status == HostStatus.PAIRING_REQUIRED) {
                currentCoroutineContext().ensureActive()
                return current
            }
            throw IOException("Cannot connect to host")
        } catch (e: HostTrustException) {
            // A trust failure is not evidence that the PC is asleep.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Host is offline/asleep
        }

        val mac = host.macAddress
        if (mac.isNullOrEmpty() || !SunshineHTTPClient.isValidMacAddress(mac)) {
            throw HostWakeFailure.MissingMAC
        }

        onWaking()
        wake(mac)
        currentCoroutineContext().ensureActive()

        val deadline = System.currentTimeMillis() + 60_000L
        for (i in 0 until attempts) {
            if (System.currentTimeMillis() >= deadline) break
            pause()
            currentCoroutineContext().ensureActive()
            try {
                val current = probe(host)
                if (current.isOnline || current.status == HostStatus.PAIRING_REQUIRED) {
                    currentCoroutineContext().ensureActive()
                    return current
                }
                throw IOException("Cannot connect to host")
            } catch (e: HostTrustException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Host not ready yet, continue polling
            }
        }

        throw HostWakeFailure.TimedOut
    }
}
