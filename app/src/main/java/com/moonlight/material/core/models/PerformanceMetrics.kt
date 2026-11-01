package com.moonlight.material.core.models

import kotlinx.serialization.Serializable

@Serializable
data class GpuMetrics(
    val id: String = "",
    val name: String = "",
    val metrics: Map<String, Double> = emptyMap()
)

@Serializable
data class CpuMetrics(
    val cpu_usage_percent: Double = 0.0
)

@Serializable
data class HostHardwareMetrics(
    val cpu: CpuMetrics = CpuMetrics(),
    val gpus: List<GpuMetrics> = emptyList()
)

@Serializable
data class PerformanceMetrics(
    val currentFps: Double = 0.0,
    val targetFps: Int = 60,
    val decodeTimeMs: Double = 0.0,
    val roundTripTimeMs: Double = 0.0,
    val roundTripTimeVarianceMs: Double = 0.0,
    val bitrateMbps: Double = 0.0,
    val packetLossPercentage: Double = 0.0,
    val networkDroppedFrames: Int = 0,
    val currentCodec: String = "H.264",
    val resolution: String = "1920x1080",
    val vsyncEnabled: Boolean = true,
    val pacingEnabled: Boolean = true
)
