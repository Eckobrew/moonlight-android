package com.moonlight.material.core.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonlight.material.core.models.AppStreamOverrides
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.networking.BonjourDiscoveryService
import com.moonlight.material.networking.PairingService
import com.moonlight.material.networking.SunshineHTTPClient
import com.moonlight.material.networking.WakeOnLAN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppState(context: Context) : ViewModel() {
    private val TAG = "APP_STATE"
    private val settingsStore = AppSettingsStore(context)
    val certificateManager = CertificateManager.initialize(context)
    val httpClient = SunshineHTTPClient(certificateManager)
    val boxArtManager = com.moonlight.material.core.utils.BoxArtManager(context, httpClient)
    val pairingService = PairingService(httpClient, certificateManager)
    private val discoveryService = BonjourDiscoveryService(context)
    private val networkReachability = com.moonlight.material.networking.NetworkReachability(context)
    private var pendingRemoteDiscovery = false

    val sessionController = StreamSessionController(httpClient)

    private val _settings = MutableStateFlow(settingsStore.loadSettings())
    val settings: StateFlow<StreamSettings> = _settings.asStateFlow()

    private val _hosts = MutableStateFlow(settingsStore.loadHosts())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()

    private val _selectedHost = MutableStateFlow<Host?>(null)
    val selectedHost: StateFlow<Host?> = _selectedHost.asStateFlow()

    private val _appsForSelectedHost = MutableStateFlow<List<StreamApp>>(emptyList())
    val appsForSelectedHost: StateFlow<List<StreamApp>> = _appsForSelectedHost.asStateFlow()

    private var appsJob: Job? = null
    val appsError = MutableStateFlow<String?>(null)

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _isRefreshingHosts = MutableStateFlow(false)
    val isRefreshingHosts: StateFlow<Boolean> = _isRefreshingHosts.asStateFlow()

    // Dialog & Screen Visibility
    val isShowingSettings = MutableStateFlow(false)
    val isShowingAddHost = MutableStateFlow(false)
    val isShowingLogs = MutableStateFlow(false)
    val hostDetailsToShow = MutableStateFlow<Host?>(null)
    val pairingHost = MutableStateFlow<Host?>(null)
    val pairingPin = MutableStateFlow<String?>(null)
    val pairingStageMessage = MutableStateFlow<String?>(null)
    val perAppSettingsApp = MutableStateFlow<StreamApp?>(null)

    val hostActionMessage = MutableStateFlow<String?>(null)

    init {
        // Start mDNS host discovery if enabled
        if (_settings.value.features.enableMdns) {
            discoveryService.startDiscovery()
            viewModelScope.launch {
                discoveryService.discoveredHosts.collect { discovered ->
                    handleDiscoveredHost(discovered.name, discovered.hostAddress, discovered.port)
                }
            }
        }

        networkReachability.startListening()
        viewModelScope.launch {
            var first = true
            var lastNetwork: Long? = null
            networkReachability.reachabilityState.collect { network ->
                if (first || (network.isConnected && network.networkHandle != lastNetwork)) {
                    first = false
                    lastNetwork = network.networkHandle
                    refreshHosts(forceRemoteVerification = network.isConnected)
                }
            }
        }
    }

    fun reloadSettings() { _settings.value = settingsStore.loadSettings() }

    fun updateSettings(newSettings: StreamSettings) {
        _settings.value = newSettings
        settingsStore.saveSettings(newSettings)
    }

    fun refreshHosts(forceRemoteVerification: Boolean = false) {
        if (_isRefreshingHosts.value) {
            if (forceRemoteVerification) pendingRemoteDiscovery = true
            return
        }
        _isRefreshingHosts.value = true
        viewModelScope.launch {
            try {
                for (host in _hosts.value.toList()) {
                    val refreshed = try {
                        httpClient.fetchServerInfo(host, discoverRemote = true, forceRemoteVerification = forceRemoteVerification).first
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        host.copy(status = HostStatus.OFFLINE)
                    }
                    // A refresh must never resurrect a deleted host or overwrite a pairing/action.
                    if (_hosts.value.find { it.id == host.id } == host) {
                        addOrUpdateHost(refreshed)
                    }
                }
            } finally {
                _isRefreshingHosts.value = false
                if (pendingRemoteDiscovery) {
                    pendingRemoteDiscovery = false
                    refreshHosts(forceRemoteVerification = true)
                }
            }
        }
    }

    private fun handleDiscoveredHost(name: String, address: String, port: Int) {
        viewModelScope.launch {
            val existing = _hosts.value.find { it.address == address || (it.name == name && it.address == address) }
            if (existing == null) {
                val newHost = Host(
                    name = name,
                    address = address,
                    httpPort = port,
                    httpsPort = port - 5,
                    status = HostStatus.UNKNOWN
                )
                try {
                    val (probed, _) = httpClient.fetchServerInfo(newHost)
                    addOrUpdateHost(probed)
                } catch (e: Exception) {
                    addOrUpdateHost(newHost)
                }
            }
        }
    }

    fun addManualHost(name: String, address: String, port: Int) {
        viewModelScope.launch {
            val candidate = Host(
                name = name.ifEmpty { "Host ($address)" },
                address = address,
                httpPort = port,
                httpsPort = port - 5,
                status = HostStatus.UNKNOWN
            )
            try {
                val (refreshed, _) = httpClient.fetchServerInfo(candidate)
                addOrUpdateHost(refreshed)
            } catch (e: Exception) {
                addOrUpdateHost(candidate.copy(status = HostStatus.OFFLINE))
            }
            isShowingAddHost.value = false
        }
    }

    fun addOrUpdateHost(host: Host) {
        val current = _hosts.value.toMutableList()
        val index = current.indexOfFirst { it.address == host.address || (it.serverId != null && it.serverId == host.serverId) }
        if (index >= 0) {
            val existing = current[index]
            current[index] = if (existing.id != host.id) {
                host.copy(
                    id = existing.id,
                    isPaired = existing.isPaired,
                    serverCertificateDER = existing.serverCertificateDER,
                    remoteAddress = existing.remoteAddress ?: host.remoteAddress,
                    remoteHTTPPort = existing.remoteHTTPPort ?: host.remoteHTTPPort,
                    remoteHTTPSPort = existing.remoteHTTPSPort ?: host.remoteHTTPSPort,
                    remoteAddressCandidate = existing.remoteAddressCandidate ?: host.remoteAddressCandidate,
                    remoteAddressVerification = existing.remoteAddressVerification ?: host.remoteAddressVerification
                )
            } else host
        } else {
            current.add(host)
        }
        _hosts.value = current
        settingsStore.saveHosts(current)
        _selectedHost.value?.let { selected ->
            _selectedHost.value = current.find { it.id == selected.id }
        }
        hostDetailsToShow.value?.let { details ->
            hostDetailsToShow.value = current.find { it.id == details.id }
        }
    }

    fun discoverRemoteAddress(host: Host) {
        viewModelScope.launch {
            try {
                val updated = httpClient.fetchServerInfo(host, discoverRemote = true, forceRemoteVerification = true).first
                if (_hosts.value.any { it.id == host.id }) addOrUpdateHost(updated)
            } catch (e: Exception) {
                hostActionMessage.value = "Remote address discovery failed: ${e.message}"
            }
        }
    }

    fun deleteHost(host: Host) {
        val current = _hosts.value.toMutableList()
        current.removeAll { it.id == host.id }
        _hosts.value = current
        settingsStore.saveHosts(current)
        if (_selectedHost.value?.id == host.id) {
            deselectHost()
        }
    }

    fun unpairHost(host: Host) {
        viewModelScope.launch {
            try {
                if (!httpClient.unpair(host)) {
                    hostActionMessage.value = "Could not unpair ${host.name}. Check that the host is reachable."
                    return@launch
                }
            } catch (e: Exception) {
                hostActionMessage.value = "Could not unpair ${host.name}: ${e.message}"
                return@launch
            }
            val updated = host.copy(
                isPaired = false,
                serverCertificateDER = null,
                status = HostStatus.PAIRING_REQUIRED
            )
            addOrUpdateHost(updated)
        }
    }

    fun wakeHost(host: Host) {
        viewModelScope.launch {
            try {
                val resolved = com.moonlight.material.networking.HostWakeConnection.resolve(
                    host = host,
                    probe = { httpClient.fetchServerInfo(it).first },
                    onWaking = { addOrUpdateHost(host.copy(status = HostStatus.WAKING)) }
                )
                addOrUpdateHost(resolved)
                hostActionMessage.value = "${resolved.name} is now awake and ready!"
            } catch (e: com.moonlight.material.networking.HostWakeFailure) {
                addOrUpdateHost(host.copy(status = HostStatus.OFFLINE))
                hostActionMessage.value = e.message ?: "Wake-on-LAN failed."
            } catch (e: Exception) {
                addOrUpdateHost(host.copy(status = HostStatus.OFFLINE))
                hostActionMessage.value = e.message ?: "Failed to wake host."
            }
        }
    }

    fun sleepHost(host: Host) {
        viewModelScope.launch {
            try {
                httpClient.sleepHost(host)
                hostActionMessage.value = "The host accepted the sleep request. Completion depends on Windows."
                addOrUpdateHost(host.copy(status = HostStatus.OFFLINE))
            } catch (e: Exception) {
                hostActionMessage.value = "${e.message} No automatic retry was made."
            }
        }
    }

    private var pairingJob: Job? = null

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingHost.value = null
        pairingPin.value = null
        pairingStageMessage.value = null
    }

    fun startPairing(host: Host) {
        if (pairingJob?.isActive == true) return
        pairingHost.value = host
        pairingPin.value = null
        pairingStageMessage.value = "Generating PIN…"

        pairingJob = viewModelScope.launch {
            try {
                val pairedHost = pairingService.pair(
                    host = host,
                    onPinGenerated = { pin ->
                        pairingPin.value = pin
                    },
                    onProgress = { stage, message ->
                        pairingStageMessage.value = "[Stage $stage/5] $message"
                    }
                )
                addOrUpdateHost(pairedHost)
                refreshHosts(forceRemoteVerification = true)
                pairingHost.value = null
                pairingPin.value = null
                hostActionMessage.value = "Successfully paired with ${pairedHost.name}!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pairingStageMessage.value = "Pairing failed: ${e.message}"
                delay(3000)
                pairingHost.value = null
            }
        }
    }

    fun selectHost(host: Host) {
        if (_selectedHost.value?.id != host.id) {
            appsJob?.cancel()
            _appsForSelectedHost.value = emptyList()
            appsError.value = null
        }
        _selectedHost.value = host
        loadApps(host)
    }

    fun deselectHost() {
        appsJob?.cancel()
        _selectedHost.value = null
        _appsForSelectedHost.value = emptyList()
        appsError.value = null
        _isLoadingApps.value = false
    }

    fun loadApps(host: Host) {
        if (_selectedHost.value?.id != host.id || appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            _isLoadingApps.value = true
            appsError.value = null
            try {
                val resolved = httpClient.fetchServerInfo(host).first
                val apps = httpClient.fetchAppList(resolved)
                if (_selectedHost.value?.id == host.id) {
                    addOrUpdateHost(resolved)
                    _appsForSelectedHost.value = apps
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("Failed to load apps: ${e.message}", TAG)
                if (_selectedHost.value?.id == host.id) {
                    appsError.value = "Could not refresh applications: ${e.message}"
                }
            } finally {
                if (_selectedHost.value?.id == host.id) _isLoadingApps.value = false
            }
        }
    }

    override fun onCleared() {
        discoveryService.stopDiscovery()
        networkReachability.stopListening()
        super.onCleared()
    }

    fun quitRunningAppOnHost(host: Host) {
        viewModelScope.launch {
            try {
                if (!httpClient.quitApp(host)) {
                    hostActionMessage.value = "The host did not confirm quitting the application."
                    return@launch
                }
            } catch (e: Exception) {
                hostActionMessage.value = "Could not quit the application: ${e.message}"
                return@launch
            }
            delay(500)
            loadApps(host)
            refreshHosts()
        }
    }

    fun getAppOverrides(host: Host, app: StreamApp): AppStreamOverrides {
        return settingsStore.loadAppOverrides(host.id, app.id)
    }

    fun saveAppOverrides(host: Host, app: StreamApp, overrides: AppStreamOverrides) {
        settingsStore.saveAppOverrides(host.id, app.id, overrides)
    }

    fun getEffectiveSettingsForApp(host: Host, app: StreamApp): StreamSettings {
        val overrides = getAppOverrides(host, app)
        return overrides.applyTo(_settings.value)
    }
}
