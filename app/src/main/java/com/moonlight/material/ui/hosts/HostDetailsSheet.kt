package com.moonlight.material.ui.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.utils.ConnectionDiagnostics
import com.moonlight.material.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailsSheet(
    host: Host,
    onDismiss: () -> Unit,
    onWakeOnLan: () -> Unit,
    onSleep: () -> Unit = {},
    settings: com.moonlight.material.core.models.StreamSettings = com.moonlight.material.core.models.StreamSettings(),
    onDiscoverRemoteAddress: () -> Unit = {}
) {
    var showSleepConfirmation by remember { mutableStateOf(false) }
    var testingPorts by remember { mutableStateOf(false) }
    var portResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val network = remember(context) { com.moonlight.material.networking.NetworkReachability(context) }
    val networkState by network.reachabilityState.collectAsState()
    DisposableEffect(network) {
        network.startListening()
        onDispose { network.stopListening() }
    }
    val useRemoteBitrate = host.isRemoteConnection || networkState.isMetered

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MidnightBlue,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Host Details: ${host.name}",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            DetailRow("Status", host.status.name)
            DetailRow("Active Address", host.effectiveAddress)
            DetailRow("Remote Address", host.remoteAddress ?: host.remoteAddressCandidate ?: "Not discovered yet")
            DetailRow("Remote Access", host.remoteAddressVerification ?: if (host.remoteAddress != null) "Saved; verified on connection" else "Discover or configure below")
            DetailRow("Connection", if (host.isRemoteConnection) "Remote" else "Primary / Local")
            DetailRow("Network", "${networkState.connectionType.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (networkState.isMetered) "Metered" else "Unmetered"}")
            DetailRow("Stream Bitrate", "${settings.streamBitrateKbps(useRemoteBitrate) / 1000.0} Mbps")
            DetailRow("Bitrate Profile", if (useRemoteBitrate && settings.enableRemoteBitrate) "Remote / metered" else "Standard")
            if (host.remoteAddress != null) {
                DetailRow("Remote HTTP Port", (host.remoteHTTPPort ?: host.httpPort).toString())
                DetailRow("Remote HTTPS Port", (host.remoteHTTPSPort ?: host.httpsPort).toString())
            }
            TextButton(onClick = onDiscoverRemoteAddress, modifier = Modifier.fillMaxWidth()) {
                Text("Discover Remote Address")
            }
            DetailRow("HTTP Port", host.effectiveHTTPPort.toString())
            DetailRow("HTTPS Port", host.effectiveHTTPSPort.toString())
            DetailRow("Server Version", host.serverVersion.ifEmpty { "Unknown" })
            DetailRow("GFE Version", host.gfeVersion.ifEmpty { "Unknown" })
            DetailRow("MAC Address", host.macAddress ?: "Not reported")
            DetailRow("Server ID", host.serverId ?: "None")
            DetailRow("Paired", if (host.isSecurelyPaired) "Yes (TLS Pinned)" else if (host.isPaired) "Yes (Unpinned)" else "No")
            DetailRow("Extended Controllers", if (host.supportsExtendedControllers) "Yes (16 slots)" else "Standard (4 slots)")

            val caps = host.capabilities
            if (caps != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = CardNavy)
                Spacer(modifier = Modifier.height(8.dp))

                if (caps.virtualDisplayCapable) {
                    DetailRow("Virtual Display Driver", if (caps.virtualDisplayReady) "Ready" else "Not Ready")
                }
                if (caps.permission != null) {
                    DetailRow("Permissions", "Launch: ${if (caps.canLaunch) "Allowed" else "Denied"} · View: ${if (caps.canView) "Allowed" else "Denied"}")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Network port connectivity test
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardNavy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Network Port Test",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Checks connectivity to Moonlight public test servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            testingPorts = true
                            portResult = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        MoonBridge.testClientConnectivity("qt.conntest.moonlight-stream.org", 443, -1)
                                    } catch (e: Throwable) {
                                        -1
                                    }
                                }
                                portResult = when (result) {
                                    -1 -> "Test inconclusive: could not reach test server."
                                    0 -> "All tested ports are reachable from this network."
                                    else -> "This network blocks: ${ConnectionDiagnostics.ports(result)}."
                                }
                                testingPorts = false
                            }
                        },
                        enabled = !testingPorts,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.NetworkCheck, null, tint = DeepNavy, modifier = Modifier.align(Alignment.CenterStart))
                            Text(
                                text = if (testingPorts) "Testing…" else "Test Connectivity",
                                color = DeepNavy,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                            )
                        }
                    }
                    if (portResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = portResult ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanGlow
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions: Put PC to Sleep & Wake-on-LAN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (caps?.sleepAvailable == true) {
                    OutlinedButton(
                        onClick = { showSleepConfirmation = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning)
                    ) {
                        Icon(Icons.Default.Bedtime, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sleep PC")
                    }
                }
                if (!host.macAddress.isNullOrEmpty()) {
                    Button(
                        onClick = onWakeOnLan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null, tint = DeepNavy)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Wake PC", color = DeepNavy, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showSleepConfirmation) {
        AlertDialog(
            onDismissRequest = { showSleepConfirmation = false },
            title = { Text("Put ${host.name} to sleep?", color = TextPrimary) },
            text = {
                Text(
                    "All active host sessions must be ended first. You can use Wake-on-LAN to wake the PC later.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSleepConfirmation = false
                        onSleep()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning)
                ) {
                    Text("Put to Sleep", color = DeepNavy, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSleepConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = MidnightBlue
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            modifier = Modifier.weight(0.4f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
    }
}
