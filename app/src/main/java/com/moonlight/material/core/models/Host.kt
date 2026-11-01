package com.moonlight.material.core.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class HostStatus {
    ONLINE,
    OFFLINE,
    PAIRING_REQUIRED,
    STREAMING,
    WAKING,
    UNKNOWN
}

@Serializable
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val remoteAddress: String? = null,
    val remoteAddressCandidate: String? = null,
    val remoteAddressVerification: String? = null,
    val activeAddress: String? = null,
    val httpsPort: Int = 47984,
    val httpPort: Int = 47989,
    val remoteHTTPSPort: Int? = null,
    val remoteHTTPPort: Int? = null,
    val status: HostStatus = HostStatus.UNKNOWN,
    val serverVersion: String = "",
    val gfeVersion: String = "",
    val appVersion: String = "",
    val macAddress: String? = null,
    val serverId: String? = null,
    val serverCodecModeSupport: Int = 0,
    val isPaired: Boolean = false,
    val serverCertificateDER: String? = null, // Base64 encoded DER certificate
    val currentAppId: Int? = null,
    val currentAppUUID: String? = null,
    val capabilities: HostCapabilities? = null,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
) {
    val isSecurelyPaired: Boolean
        get() = isPaired && !serverCertificateDER.isNullOrEmpty()

    val isOnline: Boolean
        get() = status == HostStatus.ONLINE || status == HostStatus.STREAMING

    val needsCertificatePairing: Boolean
        get() = isPaired && serverCertificateDER.isNullOrEmpty()

    val isRemoteConnection: Boolean
        get() = (remoteAddress != null && effectiveAddress == remoteAddress) || isPublicIPAddress(effectiveAddress)

    val effectiveAddress: String
        get() = activeAddress ?: address

    val effectiveHTTPSPort: Int
        get() = if (effectiveAddress == remoteAddress) (remoteHTTPSPort ?: httpsPort) else httpsPort

    val effectiveHTTPPort: Int
        get() = if (effectiveAddress == remoteAddress) (remoteHTTPPort ?: httpPort) else httpPort

    val urlAddress: String
        get() = if (effectiveAddress.contains(":") && !effectiveAddress.startsWith("[")) {
            "[$effectiveAddress]"
        } else {
            effectiveAddress
        }

    val supportsExtendedControllers: Boolean
        get() {
            val parts = appVersion.split(".")
            return parts.size == 4 && (parts[3].toIntOrNull() ?: 0) < 0
        }

    companion object {
        fun isPublicIPAddress(address: String): Boolean {
            val raw = address.removePrefix("[").removeSuffix("]").lowercase()
            val parts = raw.split('.').map { it.toIntOrNull() }
            if (parts.size == 4 && parts.all { it != null && it in 0..255 }) {
                val a = parts[0]!!; val b = parts[1]!!
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254) || (a == 100 && b in 64..127))
            }
            // Global IPv6 unicast addresses are in 2000::/3. Hostnames require
            // an explicitly configured remote endpoint to avoid DNS on the UI thread.
            return raw.contains(':') && (raw.startsWith("2") || raw.startsWith("3"))
        }

        fun urlHost(address: String): String {
            return if (address.contains(":") && !address.startsWith("[")) "[$address]" else address
        }
    }
}
