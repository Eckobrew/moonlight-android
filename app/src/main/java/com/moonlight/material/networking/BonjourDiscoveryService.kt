package com.moonlight.material.networking

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class DiscoveredHost(
    val name: String,
    val hostAddress: String,
    val port: Int
)

class BonjourDiscoveryService(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _discoveredHosts = MutableSharedFlow<DiscoveredHost>(extraBufferCapacity = 64)
    val discoveredHosts: SharedFlow<DiscoveredHost> = _discoveredHosts.asSharedFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                AppLogger.info("mDNS discovery started for $regType", "DISCOVERY")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                AppLogger.debug("Found GameStream mDNS service: ${serviceInfo.serviceName}", "DISCOVERY")
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            AppLogger.warn("Failed to resolve mDNS service: $errorCode", "DISCOVERY")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val hostAddress = resolvedInfo.host?.hostAddress ?: return
                            val port = resolvedInfo.port
                            val name = resolvedInfo.serviceName.replace("\\.", ".")
                            AppLogger.info("Resolved host $name at $hostAddress:$port", "DISCOVERY")
                            _discoveredHosts.tryEmit(DiscoveredHost(name, hostAddress, port))
                        }
                    })
                } catch (e: Exception) {
                    AppLogger.warn("Failed calling resolveService: ${e.message}", "DISCOVERY")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                AppLogger.debug("Lost mDNS service: ${serviceInfo.serviceName}", "DISCOVERY")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                AppLogger.info("mDNS discovery stopped", "DISCOVERY")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                AppLogger.error("Failed to start mDNS discovery: $errorCode", "DISCOVERY")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                AppLogger.error("Failed to stop mDNS discovery: $errorCode", "DISCOVERY")
            }
        }

        try {
            nsdManager.discoverServices(
                "_nvstream._tcp.",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            AppLogger.error("Error initiating mDNS discovery: ${e.message}", "DISCOVERY")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering || nsdManager == null || discoveryListener == null) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            AppLogger.warn("Error stopping discovery: ${e.message}", "DISCOVERY")
        } finally {
            isDiscovering = false
            discoveryListener = null
        }
    }
}
