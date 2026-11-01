package com.moonlight.material.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.moonlight.material.core.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionType {
    WIFI,
    ETHERNET,
    CELLULAR,
    VPN,
    OTHER,
    NONE
}

data class NetworkReachabilityState(
    val isConnected: Boolean = false,
    val networkHandle: Long? = null,
    val connectionType: ConnectionType = ConnectionType.NONE,
    val isMetered: Boolean = false,
    val isLowLatencyPreferred: Boolean = false
)

/**
 * Monitors network reachability, connection type, and route changes.
 * Ported from Sources/MoonlightMac/Networking/NetworkReachability.swift.
 */
class NetworkReachability(private val context: Context) {
    private val TAG = "NetworkReachability"
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _reachabilityState = MutableStateFlow(getCurrentState())
    val reachabilityState: StateFlow<NetworkReachabilityState> = _reachabilityState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateState()
        }

        override fun onLost(network: Network) {
            updateState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateState()
        }
    }

    private var isListening = false

    fun startListening() {
        if (isListening || connectivityManager == null) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isListening = true
            updateState()
        } catch (e: Exception) {
            AppLogger.error("Failed to register network callback: ${e.message}", TAG)
        }
    }

    fun stopListening() {
        if (!isListening || connectivityManager == null) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isListening = false
        } catch (e: Exception) {
            AppLogger.error("Failed to unregister network callback: ${e.message}", TAG)
        }
    }

    fun updateState() {
        _reachabilityState.value = getCurrentState()
    }

    fun getCurrentState(): NetworkReachabilityState {
        val cm = connectivityManager ?: return NetworkReachabilityState()
        val activeNetwork = cm.activeNetwork ?: return NetworkReachabilityState()
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkReachabilityState()

        val isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
            else -> ConnectionType.OTHER
        }

        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isLowLatency = type == ConnectionType.ETHERNET || type == ConnectionType.WIFI

        return NetworkReachabilityState(
            isConnected = isConnected,
            networkHandle = activeNetwork.networkHandle,
            connectionType = type,
            isMetered = isMetered,
            isLowLatencyPreferred = isLowLatency
        )
    }
}
