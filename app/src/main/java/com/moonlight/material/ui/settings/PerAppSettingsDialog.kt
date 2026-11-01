package com.moonlight.material.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.core.models.AppStreamOverrides
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.VideoCodec
import com.moonlight.material.ui.theme.*

@Composable
fun PerAppSettingsDialog(
    host: Host,
    app: StreamApp,
    currentOverrides: AppStreamOverrides,
    onSave: (AppStreamOverrides) -> Unit,
    onDismiss: () -> Unit
) {
    var overrides by remember { mutableStateOf(currentOverrides) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MidnightBlue,
        title = {
            Column {
                Text(
                    text = "Settings for “${app.title}”",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Overrides apply only to this game on ${host.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Resolution override
                Text("Resolution Override", style = MaterialTheme.typography.bodyMedium, color = CyanAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = overrides.width == null,
                        onClick = { overrides = overrides.copy(width = null, height = null) },
                        label = { Text("Inherit Global") }
                    )
                    FilterChip(
                        selected = overrides.width == 1920,
                        onClick = { overrides = overrides.copy(width = 1920, height = 1080) },
                        label = { Text("1080p") }
                    )
                    FilterChip(
                        selected = overrides.width == 2560,
                        onClick = { overrides = overrides.copy(width = 2560, height = 1440) },
                        label = { Text("1440p") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Frame rate override
                Text("Frame Rate Override", style = MaterialTheme.typography.bodyMedium, color = CyanAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = overrides.fps == null,
                        onClick = { overrides = overrides.copy(fps = null) },
                        label = { Text("Inherit Global") }
                    )
                    FilterChip(
                        selected = overrides.fps == 60,
                        onClick = { overrides = overrides.copy(fps = 60) },
                        label = { Text("60fps") }
                    )
                    FilterChip(
                        selected = overrides.fps == 120,
                        onClick = { overrides = overrides.copy(fps = 120) },
                        label = { Text("120fps") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Codec override
                Text("Codec Override", style = MaterialTheme.typography.bodyMedium, color = CyanAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = overrides.codec == null,
                        onClick = { overrides = overrides.copy(codec = null) },
                        label = { Text("Inherit Global") }
                    )
                    FilterChip(
                        selected = overrides.codec == VideoCodec.HEVC,
                        onClick = { overrides = overrides.copy(codec = VideoCodec.HEVC) },
                        label = { Text("HEVC") }
                    )
                    FilterChip(
                        selected = overrides.codec == VideoCodec.AV1,
                        onClick = { overrides = overrides.copy(codec = VideoCodec.AV1) },
                        label = { Text("AV1") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(overrides)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text("Save", color = DeepNavy, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
