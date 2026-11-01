package com.moonlight.material.ui.stream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.PerformanceMetrics

/**
 * Minimalist, high-legibility text-only performance metrics HUD
 * anchored in the top-right corner of the streaming viewport.
 */
@Composable
fun PerformanceHUD(
    metrics: PerformanceMetrics,
    hostStatistics: HostHardwareMetrics? = null,
    modifier: Modifier = Modifier
) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFFEEEEEE),
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            offset = Offset(1.5f, 1.5f),
            blurRadius = 3f
        )
    )

    val warningColor = Color(0xFFFFB74D) // Amber/orange warning
    val warningStyle = textStyle.copy(color = warningColor)

    Column(
        modifier = modifier.padding(top = 8.dp, end = 12.dp),
        horizontalAlignment = Alignment.End
    ) {
        // Stream metrics
        Text(
            text = "${String.format("%.1f", metrics.currentFps)} rendered FPS (${metrics.targetFps} target) · ${metrics.resolution} · ${metrics.currentCodec}",
            style = textStyle
        )
        Text(
            text = "Decode + queue: ${String.format("%.2f", metrics.decodeTimeMs)} ms · RTT: ${String.format("%.1f", metrics.roundTripTimeMs)} ms (±${String.format("%.1f", metrics.roundTripTimeVarianceMs)})",
            style = textStyle
        )
        Text(text = "Received: ${String.format("%.1f", metrics.receivedFps)} FPS · Decoded: ${String.format("%.1f", metrics.decodedFps)} FPS", style = textStyle)
        Text(
            text = "Target bitrate: ${String.format("%.1f", metrics.bitrateMbps)} Mbps · Drops: ${metrics.networkDroppedFrames} network / ${metrics.decoderDroppedFrames} decoder",
            style = if (metrics.networkDroppedFrames > 0 || metrics.decoderDroppedFrames > 0) warningStyle else textStyle
        )

        // Host hardware metrics (if active and available)
        if (hostStatistics != null) {
            val cpuText = hostStatistics.cpu.cpu_usage_percent?.let { "Host CPU: ${String.format("%.1f", it)}%" }
            val gpu = hostStatistics.gpus.firstOrNull()
            val gpuText = gpu?.let { g ->
                val usage = g.metrics["gpu_usage_percent"]?.let { "GPU: ${String.format("%.1f", it)}%" }
                val temp = g.metrics["gpu_temperature_c"]?.let { " · ${String.format("%.0f", it)}°C" } ?: ""
                if (usage != null) "$usage$temp" else null
            }
            val hostLine = listOfNotNull(cpuText, gpuText).joinToString(" · ")
            if (hostLine.isNotEmpty()) {
                Text(text = hostLine, style = textStyle)
            }
        }
    }
}
