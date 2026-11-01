package com.moonlight.material

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.moonlight.material.core.utils.GameShortcutManager
import com.moonlight.material.core.models.StreamSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.state.AppState
import com.moonlight.material.ui.apps.AppGridScreen
import com.moonlight.material.ui.debug.LogViewerSheet
import com.moonlight.material.ui.hosts.AddHostDialog
import com.moonlight.material.ui.hosts.HostDetailsSheet
import com.moonlight.material.ui.hosts.HostGridScreen
import com.moonlight.material.ui.pairing.PairingDialog
import com.moonlight.material.ui.settings.PerAppSettingsDialog
import com.moonlight.material.ui.settings.SettingsScreen
import com.moonlight.material.ui.theme.DeepNavy
import com.moonlight.material.ui.theme.MoonlightTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    private var shortcutJob: Job? = null
    private var shortcutLaunching by mutableStateOf(false)

    private val appState by viewModels<AppState> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return AppState(applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MoonlightTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepNavy
                ) {
                    if (shortcutLaunching) {
                        AlertDialog(onDismissRequest = {}, title = { Text("Opening shortcut") },
                            text = { Text("Connecting to the PC and finding the current application…") },
                            confirmButton = { TextButton(onClick = { shortcutJob?.cancel(); shortcutLaunching = false }) { Text("Cancel") } })
                    }
                    val hostMessage by appState.hostActionMessage.collectAsState()
                    if (hostMessage != null) {
                        AlertDialog(
                            onDismissRequest = { appState.hostActionMessage.value = null },
                            title = { Text("Host") },
                            text = { Text(hostMessage!!) },
                            confirmButton = {
                                TextButton(onClick = { appState.hostActionMessage.value = null }) { Text("OK") }
                            }
                        )
                    }
                    val selectedHost by appState.selectedHost.collectAsState()
                    val isShowingSettings by appState.isShowingSettings.collectAsState()
                    val isShowingAddHost by appState.isShowingAddHost.collectAsState()
                    val isShowingLogs by appState.isShowingLogs.collectAsState()
                    val hostDetailsToShow by appState.hostDetailsToShow.collectAsState()
                    val pairingHost by appState.pairingHost.collectAsState()
                    val pairingPin by appState.pairingPin.collectAsState()
                    val pairingStageMessage by appState.pairingStageMessage.collectAsState()
                    val perAppSettingsApp by appState.perAppSettingsApp.collectAsState()
                    val currentSettings by appState.settings.collectAsState()

                    if (selectedHost != null && selectedHost!!.isSecurelyPaired) {
                        AppGridScreen(
                            host = selectedHost!!,
                            appState = appState,
                            onLaunchApp = { app ->
                                launchStream(selectedHost!!, app)
                            }
                        )
                    } else {
                        HostGridScreen(appState = appState)
                    }

                    // Add Host Dialog
                    if (isShowingAddHost) {
                        AddHostDialog(
                            onDismiss = { appState.isShowingAddHost.value = false },
                            onAdd = { name, address, port ->
                                appState.addManualHost(name, address, port)
                            }
                        )
                    }

                    // Host Details Sheet
                    if (hostDetailsToShow != null) {
                        HostDetailsSheet(
                            host = hostDetailsToShow!!,
                            settings = currentSettings,
                            onDiscoverRemoteAddress = { appState.discoverRemoteAddress(hostDetailsToShow!!) },
                            onDismiss = { appState.hostDetailsToShow.value = null },
                            onWakeOnLan = {
                                appState.wakeHost(hostDetailsToShow!!)
                                appState.hostDetailsToShow.value = null
                            },
                            onSleep = {
                                appState.sleepHost(hostDetailsToShow!!)
                                appState.hostDetailsToShow.value = null
                            }
                        )
                    }

                    // Pairing Dialog
                    if (pairingHost != null) {
                        PairingDialog(
                            host = pairingHost!!,
                            pin = pairingPin,
                            stageMessage = pairingStageMessage,
                            onCancel = { appState.cancelPairing() }
                        )
                    }

                    // Settings Screen
                    if (isShowingSettings) {
                        SettingsScreen(
                            currentSettings = currentSettings,
                            onSave = { updated -> appState.updateSettings(updated) },
                            onDismiss = { appState.isShowingSettings.value = false }
                        )
                    }

                    // Per-App Settings Dialog
                    if (perAppSettingsApp != null && selectedHost != null) {
                        val currentOverrides = appState.getAppOverrides(selectedHost!!, perAppSettingsApp!!)
                        PerAppSettingsDialog(
                            host = selectedHost!!,
                            app = perAppSettingsApp!!,
                            currentOverrides = currentOverrides,
                            onSave = { overrides ->
                                appState.saveAppOverrides(selectedHost!!, perAppSettingsApp!!, overrides)
                            },
                            onDismiss = { appState.perAppSettingsApp.value = null }
                        )
                    }

                    // Log Viewer Sheet
                    if (isShowingLogs) {
                        LogViewerSheet(
                            onDismiss = { appState.isShowingLogs.value = false }
                        )
                    }
                }
            }
        }
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(source: Intent) {
        val encoded = source.getStringExtra(GameShortcutManager.EXTRA_REQUEST) ?: return
        source.removeExtra(GameShortcutManager.EXTRA_REQUEST)
        shortcutJob?.cancel()
        shortcutJob = lifecycleScope.launch {
            shortcutLaunching = true
            try {
                val request = GameShortcutManager.decodeRequest(encoded)
                val resolved = GameShortcutManager.resolve(applicationContext, request, appState.httpClient)
                appState.addOrUpdateHost(resolved.host)
                launchStream(resolved.host, resolved.app, resolved.settings, shortcutMode = true)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                appState.hostActionMessage.value = "Could not open shortcut: ${e.message}"
            } finally { shortcutLaunching = false }
        }
    }

    override fun onResume() {
        super.onResume()
        appState.reloadSettings()
        appState.refreshHosts()
    }

    private fun launchStream(host: Host, app: StreamApp, settingsOverride: StreamSettings? = null, shortcutMode: Boolean = false) {
        val effectiveSettings = settingsOverride ?: appState.getEffectiveSettingsForApp(host, app)
        val json = Json { ignoreUnknownKeys = true }

        val intent = Intent(this, StreamActivity::class.java).apply {
            putExtra("EXTRA_SHORTCUT_MODE", shortcutMode)
            putExtra("EXTRA_HOST", json.encodeToString(host))
            putExtra("EXTRA_APP", json.encodeToString(app))
            putExtra("EXTRA_SETTINGS", json.encodeToString(effectiveSettings))
        }
        startActivity(intent)
    }
}
