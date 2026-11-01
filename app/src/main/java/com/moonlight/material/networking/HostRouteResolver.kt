package com.moonlight.material.networking

import com.moonlight.material.core.models.Host
import kotlinx.coroutines.CancellationException
import java.io.IOException

object HostRouteResolver {
    suspend fun <T> resolve(host: Host, probe: suspend (Host) -> T): T {
        var failure: Exception? = null
        val candidates = listOfNotNull(host.address,
            host.remoteAddress?.takeIf { host.isSecurelyPaired && !host.serverId.isNullOrBlank() })
            .distinct()
        for (address in candidates) {
            try {
                return probe(host.copy(activeAddress = address))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IOException("No host address available")
    }
}
