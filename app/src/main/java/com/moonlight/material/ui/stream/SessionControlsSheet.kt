package com.moonlight.material.ui.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
    val showHUD by session.showPerformanceOverlay.collectAsState()

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
                        colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                    )
                    Button(
                        onClick = { session.applyRuntimeBitrate(bitrateMbps) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Apply", color = DeepNavy, fontWeight = FontWeight.Bold)
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
