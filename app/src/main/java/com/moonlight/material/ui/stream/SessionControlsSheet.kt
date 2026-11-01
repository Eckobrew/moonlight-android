package com.moonlight.material.ui.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionControlsSheet(
    session: StreamSessionController,
    app: StreamApp,
    onShowKeyboard: () -> Unit,
    onTouchModeChanged: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit
) {
    val touchpadMode by session.touchpadMode.collectAsState()
    val isQuitting by session.isQuittingApp.collectAsState()
    val currentKbps by session.runtimeBitrateKbps.collectAsState()
    var bitrateMbps by remember { mutableStateOf((currentKbps / 1000.0).coerceAtLeast(1.0)) }
    val isApplyingBitrate by session.bitrateRequestInProgress.collectAsState()
    val showHUD by session.showPerformanceOverlay.collectAsState()
    val showHostStats by session.showHostStatistics.collectAsState()
    val hostControlMessage by session.hostControlMessage.collectAsState()
    val activeHost = session.activeHost

    var commandsMenuExpanded by remember { mutableStateOf(false) }
    var showQuitConfirmDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MidnightBlue,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: App Title and Host
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Streaming from ${activeHost?.name ?: "Host"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Menu", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Feedback Message
            if (!hostControlMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = hostControlMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanGlow
                )
            }

            // Quick Action: Show Keyboard
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onShowKeyboard()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Show On-Screen Keyboard",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Touch Input Mode", color = TextPrimary, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !touchpadMode, onClick = {
                    session.setTouchpadMode(false)
                    onTouchModeChanged(false)
                }, label = { Text("Touchscreen") })
                FilterChip(selected = touchpadMode, onClick = {
                    session.setTouchpadMode(true)
                    onTouchModeChanged(true)
                }, label = { Text("Touchpad") })
            }
            Spacer(Modifier.height(12.dp))

            // Set Bitrate Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardNavy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set Bitrate",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${String.format("%.1f", bitrateMbps)} Mbps",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = bitrateMbps.toFloat(),
                        onValueChange = { bitrateMbps = it.toDouble() },
                        valueRange = 1f..150f,
                        enabled = !isApplyingBitrate,
                        colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Preset Chips and Apply Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(10.0, 20.0, 50.0, 80.0).forEach { preset ->
                            FilterChip(
                                selected = kotlin.math.abs(bitrateMbps - preset) < 0.5,
                                onClick = { bitrateMbps = preset },
                                label = { Text("${preset.toInt()}M") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanAccent,
                                    selectedLabelColor = DeepNavy
                                )
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = { session.applyRuntimeBitrate(bitrateMbps) },
                            enabled = !isApplyingBitrate,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                        ) {
                            Text(
                                text = if (isApplyingBitrate) "Applying…" else "Apply",
                                color = DeepNavy,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                Column {
                    Text("Performance HUD", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text("Monospace text metrics in top right", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
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
                        Text("Host CPU / GPU Statistics", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            "Samples Sunshine CPU & GPU usage on HUD",
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

            Spacer(modifier = Modifier.height(20.dp))

            Spacer(Modifier.height(20.dp))
            // Disconnect button
            Button(
                onClick = {
                    onDismiss()
                    session.disconnectStream()
                    onFinish()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CardNavy),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, null, tint = TextPrimary, modifier = Modifier.align(Alignment.CenterStart))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Disconnect Stream", color = TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("Leave game running on host to resume later", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quit Game button
            Button(
                onClick = { showQuitConfirmDialog = true },
                enabled = !isQuitting,
                colors = ButtonDefaults.buttonColors(containerColor = RoseError.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PowerSettingsNew, null, tint = TextPrimary, modifier = Modifier.align(Alignment.CenterStart))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isQuitting) "Quitting…" else "Quit Game", color = TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("Terminate app on host PC", style = MaterialTheme.typography.bodySmall, color = TextPrimary.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    }
                }
            }


            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Quit Game Confirmation Dialog
    if (showQuitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showQuitConfirmDialog = false },
            title = {
                Text(
                    text = "Quit Game on Host?",
                    color = RoseError,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to terminate ${app.title}? Any unsaved progress on the host will be lost.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showQuitConfirmDialog = false
                        session.quitRunningApp()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError)
                ) {
                    Text("Quit Game", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = MidnightBlue
        )
    }
}
