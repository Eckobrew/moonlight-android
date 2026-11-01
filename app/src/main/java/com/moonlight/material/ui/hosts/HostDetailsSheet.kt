package com.moonlight.material.ui.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.core.models.Host
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailsSheet(
    host: Host,
    onDismiss: () -> Unit,
    onWakeOnLan: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MidnightBlue,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Host Details: ${host.name}",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            DetailRow("Status", host.status.name)
            DetailRow("Address", host.effectiveAddress)
            DetailRow("HTTP Port", host.effectiveHTTPPort.toString())
            DetailRow("HTTPS Port", host.effectiveHTTPSPort.toString())
            DetailRow("Server Version", host.serverVersion.ifEmpty { "Unknown" })
            DetailRow("GFE Version", host.gfeVersion.ifEmpty { "Unknown" })
            DetailRow("MAC Address", host.macAddress ?: "Not reported")
            DetailRow("Server ID", host.serverId ?: "None")
            DetailRow("Paired", if (host.isSecurelyPaired) "Yes (TLS Pinned)" else if (host.isPaired) "Yes (Unpinned)" else "No")
            DetailRow("Supports Extended Controllers", if (host.supportsExtendedControllers) "Yes (16 slots)" else "Standard (4 slots)")

            Spacer(modifier = Modifier.height(24.dp))

            if (!host.macAddress.isNullOrEmpty()) {
                Button(
                    onClick = onWakeOnLan,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PowerSettingsNew, null, tint = DeepNavy)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Wake-on-LAN", color = DeepNavy, fontWeight = FontWeight.Bold)
                }
            }
        }
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
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
