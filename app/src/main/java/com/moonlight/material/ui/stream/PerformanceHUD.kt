package com.moonlight.material.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.core.models.PerformanceMetrics
import com.moonlight.material.ui.theme.*

@Composable
fun PerformanceHUD(
    metrics: PerformanceMetrics,
    modifier: Modifier = Modifier
) {
    val statusColor = if (metrics.packetLossPercentage > 1.0) RoseError else if (metrics.packetLossPercentage > 0.3) AmberWarning else EmeraldGreen

    Box(
        modifier = modifier
            .width(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MidnightBlue.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PERFORMANCE HUD",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))

            MetricRow("FPS", String.format("%.1f / %d", metrics.currentFps, metrics.targetFps))
            MetricRow("Decode Time", String.format("%.2f ms", metrics.decodeTimeMs))
            MetricRow("RTT Latency", String.format("%.1f ms", metrics.roundTripTimeMs))
            MetricRow("RTT Variation", String.format("%.1f ms", metrics.roundTripTimeVarianceMs))
            MetricRow("Bitrate target", String.format("%.1f Mbps", metrics.bitrateMbps))
            MetricRow(
                "Packet Loss",
                String.format("%.1f%% (%d)", metrics.packetLossPercentage, metrics.networkDroppedFrames),
                isWarning = metrics.packetLossPercentage > 0.5
            )
            MetricRow("Codec", metrics.currentCodec)
            MetricRow("Resolution", metrics.resolution)
            MetricRow("V-Sync", if (metrics.vsyncEnabled) "On" else "Off")
            MetricRow("Pacing", if (metrics.pacingEnabled) "On" else "Off")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, isWarning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isWarning) AmberWarning else TextPrimary
        )
    }
}
