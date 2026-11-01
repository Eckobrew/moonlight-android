package com.moonlight.material.core.models

import kotlinx.serialization.Serializable

@Serializable
data class AppStreamOverrides(
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrateMbps: Int? = null,
    val codec: VideoCodec? = null,
    val audioMode: AudioConfigurationMode? = null,
    val enableHdr: Boolean? = null,
    val enableVsync: Boolean? = null,
    val optimizeGameSettings: Boolean? = null,
    val quitAppOnStop: Boolean? = null,
    val remoteMonitorProfile: Boolean? = null
) {
    fun applyTo(base: StreamSettings): StreamSettings {
        return base.copy(
            width = width ?: base.width,
            height = height ?: base.height,
            fps = fps ?: base.fps,
            bitrateMbps = bitrateMbps ?: base.bitrateMbps,
            codec = codec ?: base.codec,
            audioMode = audioMode ?: base.audioMode,
            enableHdr = enableHdr ?: base.enableHdr,
            enableVsync = enableVsync ?: base.enableVsync,
            optimizeGameSettings = optimizeGameSettings ?: base.optimizeGameSettings,
            quitAppOnStop = quitAppOnStop ?: base.quitAppOnStop
        )
    }
}
