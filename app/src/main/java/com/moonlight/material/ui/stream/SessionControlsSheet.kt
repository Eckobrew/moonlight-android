package com.moonlight.material.ui.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionControlsSheet(
    session: StreamSessionController,
    onDismiss: () -> Unit
) {
    val currentKbps by session.runtimeBitrateKbps.collectAsState()
    var bitrateMbps by remember { mutableStateOf((currentKbps / 1000.0).coerceAtLeast(1.0)) }
    val isApplyingBitrate by session.bitrateRequestInProgress.collectAsState()
    val showHUD by session.showPerformanceOverlay.collectAsState()
    val showHostStats by session.showHostStatistics.collectAsState()
    val hostControlMessage by session.hostControlMessage.collectAsState()
    val activeHost = session.activeHost

    var commandsMenuExpanded by remember { mutableStateOf(false) }

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
                text = "Session Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Live runtime bitrate adjustment
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardNavy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Bitrate: ${String.format("%.1f", bitrateMbps)} Mbps",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanAccent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = bitrateMbps.toFloat(),
                        onValueChange = { bitrateMbps = it.toDouble() },
                        valueRange = 1f..150f,
                        enabled = !isApplyingBitrate,
                        colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                    )
                    Button(
                        onClick = { session.applyRuntimeBitrate(bitrateMbps) },
                        enabled = !isApplyingBitrate,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = if (isApplyingBitrate) "Applying…" else "Apply",
                            color = DeepNavy,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Show HUD toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show Performance HUD", color = TextPrimary)
                Switch(
                    checked = showHUD,
                    onCheckedChange = { session.togglePerformanceOverlay() },
                    colors = SwitchDefaults.colors(checkedThumbColor = DeepNavy, checkedTrackColor = CyanAccent)
                )
            }

            // Include Host Statistics toggle (if supported)
            if (activeHost?.capabilities?.hardwareMetricsVersion == 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include Host Statistics", color = TextPrimary)
                        Text(
                            "Samples CPU/GPU telemetry while HUD is visible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = showHostStats,
                        onCheckedChange = { session.toggleHostStatistics() },
                        colors = SwitchDefaults.colors(checkedThumbColor = DeepNavy, checkedTrackColor = CyanAccent)
                    )
                }
            }

            // Server Commands (if supported)
            val commands = activeHost?.capabilities?.commands.orEmpty()
            if (activeHost?.capabilities?.canRunCommands == true && commands.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { commandsMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
                    ) {
                        Icon(Icons.Default.Terminal, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Server Commands (${commands.size})")
                    }
                    DropdownMenu(
                        expanded = commandsMenuExpanded,
                        onDismissRequest = { commandsMenuExpanded = false },
                        containerColor = MidnightBlue
                    ) {
                        commands.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name, color = TextPrimary) },
                                onClick = {
                                    commandsMenuExpanded = false
                                    session.runServerCommand(index, name)
                                }
                            )
                        }
                    }
                }
            }

            // Release Remote Monitor button
            if (session.isRemoteMonitorSession) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        session.disconnectRemoteMonitor()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning)
                ) {
                    Icon(Icons.Default.Monitor, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Release Remote Monitor")
                }
            }

            // Feedback Message
            if (!hostControlMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = hostControlMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanGlow
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Disconnect button
            Button(
                onClick = {
                    session.stopSession()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect Stream", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
