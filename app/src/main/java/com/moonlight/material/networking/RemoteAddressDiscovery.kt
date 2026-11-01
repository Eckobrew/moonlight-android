package com.moonlight.material.networking

import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.models.Host
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** STUN finds this client's WAN address; infer a host candidate only on a physical local subnet. */
class RemoteAddressDiscovery(
    private val query: suspend () -> String? = {
        withContext(Dispatchers.IO) { MoonBridge.findExternalAddressIP4("stun.moonlight-stream.org", 3478) }
    },
    private val isLocal: (String) -> Boolean = ::isDirectlyConnectedIPv4,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private data class Attempt(val date: Long, val host: Host)
    private val attempts = mutableMapOf<String, Attempt>()
    private var stunDate = 0L
    private var stunAddress: String? = null

    suspend fun update(host: Host, reportedAddress: String?, reportedHTTPPort: Int?, force: Boolean = false,
        verify: suspend (String, Int, Int) -> Boolean): Host = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!host.isSecurelyPaired || host.serverId.isNullOrBlank()) return@withLock host
            val key = "${host.id}:${host.serverCertificateDER}:${host.serverId}:$reportedAddress:$reportedHTTPPort"
            val previous = attempts[key]
            if (!force && previous != null && now() - previous.date < 15_000) {
                val cached = previous.host
                return@withLock host.copy(remoteAddress = cached.remoteAddress ?: host.remoteAddress,
                    remoteHTTPPort = cached.remoteHTTPPort ?: host.remoteHTTPPort,
                    remoteHTTPSPort = cached.remoteHTTPSPort ?: host.remoteHTTPSPort,
                    remoteAddressCandidate = cached.remoteAddressCandidate,
                    remoteAddressVerification = cached.remoteAddressVerification)
            }
            if (!force && host.remoteAddress != null && previous != null && now() - previous.date < 300_000) return@withLock host
            var candidate = reportedAddress?.trim()?.takeIf { it.isNotEmpty() && it != "0.0.0.0" && it != "::" }
            if (candidate == null && isLocal(host.address)) {
                if (force || now() - stunDate > if (stunAddress == null) 15_000 else 300_000) {
                    stunDate = now()
                    stunAddress = try { query() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                }
                candidate = stunAddress
            }
            var result = host.copy(remoteAddressCandidate = candidate,
                remoteAddressVerification = "No remote address reported; local WAN discovery was unavailable.")
            if (candidate != null && candidate != host.address) {
                val httpPort = reportedHTTPPort?.takeIf { it in 1..65535 } ?: host.httpPort
                val ports = listOfNotNull(host.remoteHTTPSPort, host.httpsPort, (httpPort - 5).takeIf { it > 0 }).distinct()
                result = result.copy(remoteAddressVerification = "Discovered, but not verified. Check port forwarding or router loopback support.")
                for (httpsPort in ports) {
                    val accepted = try { verify(candidate, httpPort, httpsPort) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (accepted) {
                        result = result.copy(remoteAddress = candidate, remoteHTTPPort = httpPort,
                            remoteHTTPSPort = httpsPort, remoteAddressVerification = "Verified: certificate and server ID match.")
                        break
                    }
                }
            }
            attempts[key] = Attempt(now(), result)
            result
        }
    }

    companion object {
        fun isDirectlyConnectedIPv4(address: String): Boolean = try {
            val target = InetAddress.getByName(address) as? Inet4Address
            if (target == null || target.isLoopbackAddress || !target.isSiteLocalAddress) false
            else NetworkInterface.getNetworkInterfaces().toList().any { network ->
                network.isUp && !network.isLoopback && !network.isPointToPoint &&
                    (network.name.startsWith("wlan") || network.name.startsWith("eth")) &&
                    network.interfaceAddresses.any { local ->
                        val ip = local.address
                        val prefix = local.networkPrefixLength.toInt()
                        if (ip !is Inet4Address || prefix !in 1..32) false else {
                            fun number(bytes: ByteArray) = bytes.fold(0) { a, b -> (a shl 8) or (b.toInt() and 255) }
                            val mask = -1 shl (32 - prefix)
                            (number(target.address) and mask) == (number(ip.address) and mask)
                        }
                    }
            }
        } catch (_: Exception) { false }
    }
}
