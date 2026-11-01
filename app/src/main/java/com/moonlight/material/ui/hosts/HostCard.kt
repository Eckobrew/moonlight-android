package com.moonlight.material.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostStatus
import com.moonlight.material.ui.theme.*

@Composable
fun HostCard(
    host: Host,
    onSelect: () -> Unit,
    onPair: () -> Unit,
    onWake: () -> Unit,
    onDetails: () -> Unit,
    onUnpair: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val statusColor = when (host.status) {
        HostStatus.ONLINE -> EmeraldGreen
        HostStatus.OFFLINE -> Color(0xFF64748B)
        HostStatus.STREAMING -> CyanAccent
        HostStatus.PAIRING_REQUIRED -> AmberWarning
        HostStatus.WAKING -> CyanGlow
        HostStatus.UNKNOWN -> Color(0xFF94A3B8)
    }

    val statusText = when (host.status) {
        HostStatus.ONLINE -> "Online"
        HostStatus.OFFLINE -> "Offline"
        HostStatus.STREAMING -> "Streaming"
        HostStatus.PAIRING_REQUIRED -> "Pairing Required"
        HostStatus.WAKING -> "Waking…"
        HostStatus.UNKNOWN -> "Unknown"
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = CardNavy
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (host.isSecurelyPaired) onSelect() else onPair()
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Host icon with status indicator
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightBlue)
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // More options menu
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = MidnightBlue
                    ) {
                        if (host.isSecurelyPaired) {
                            DropdownMenuItem(
                                text = { Text("Open Apps") },
                                onClick = { menuExpanded = false; onSelect() },
                                leadingIcon = { Icon(Icons.Default.Apps, null) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Pair with Host") },
                                onClick = { menuExpanded = false; onPair() },
                                leadingIcon = { Icon(Icons.Default.Link, null) }
                            )
                        }
                        if (!host.macAddress.isNullOrEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Wake-on-LAN") },
                                onClick = { menuExpanded = false; onWake() },
                                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Host Details") },
                            onClick = { menuExpanded = false; onDetails() },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                        if (host.isPaired) {
                            DropdownMenuItem(
                                text = { Text("Unpair") },
                                onClick = { menuExpanded = false; onUnpair() },
                                leadingIcon = { Icon(Icons.Default.LinkOff, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = RoseError) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = RoseError) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Host Name & Address
            Text(
                text = host.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = host.effectiveAddress,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Status pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

            }
        }
    }
}
