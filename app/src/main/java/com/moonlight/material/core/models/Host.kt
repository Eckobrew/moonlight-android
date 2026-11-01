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
        fun urlHost(address: String): String {
            return if (address.contains(":") && !address.startsWith("[")) "[$address]" else address
        }
    }
}
