package com.moonlight.material

import com.moonlight.material.core.models.Host
import com.moonlight.material.networking.HostRouteResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HostRouteResolverTest {
    private val host = Host(name = "PC", address = "192.168.1.10", remoteAddress = "remote.example",
        isPaired = true, serverId = "server", serverCertificateDER = "pin",
        remoteHTTPPort = 58000, remoteHTTPSPort = 57995)

    @Test fun failedLocalUsesRemotePortsAndPreservesIdentity() = runBlocking {
        val attempts = mutableListOf<Host>()
        val result = HostRouteResolver.resolve(host) {
            attempts += it
            if (it.effectiveAddress == host.address) throw IOException("offline")
            it
        }
        assertEquals(listOf(host.address, host.remoteAddress), attempts.map { it.effectiveAddress })
        assertEquals(57995, result.effectiveHTTPSPort)
        assertEquals(58000, result.effectiveHTTPPort)
        assertEquals(host.serverId, result.serverId)
        assertEquals(host.serverCertificateDER, result.serverCertificateDER)
        assertTrue(result.isRemoteConnection)
    }
    @Test fun workingLocalDoesNotProbeRemote() = runBlocking {
        var count = 0
        val result = HostRouteResolver.resolve(host) { count++; it }
        assertEquals(1, count)
        assertFalse(result.isRemoteConnection)
    }
    @Test fun unpairedHostNeverUsesRemoteFallback() = runBlocking {
        var count = 0
        try {
            HostRouteResolver.resolve(host.copy(isPaired = false)) { count++; throw IOException("offline") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertEquals(1, count)
    }
    @Test fun cancellationDoesNotTryAnotherEndpoint() = runBlocking {
        var count = 0
        try {
            HostRouteResolver.resolve(host) { count++; throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, count)
    }
}
