package com.moonlight.material.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class VideoCodec(val displayName: String) {
    AUTO("Auto"),
    H264("H.264"),
    HEVC("HEVC (H.265)"),
    AV1("AV1");

    fun limelightMask(enableHdr: Boolean = false, yuv444: Boolean = false): Int {
        val hevcMask = if (enableHdr) (0x0002 or 0x0004) else 0x0002
        val av1Mask = if (enableHdr) (0x0008 or 0x0010) else 0x0008
        val baseMask = when (this) {
            AUTO -> 0x0001 or hevcMask or av1Mask
            H264 -> 0x0001
            HEVC -> hevcMask
            AV1 -> av1Mask
        }
        return if (yuv444 && this != AV1) baseMask or 0x0100 else baseMask
    }
}

@Serializable
enum class AudioConfigurationMode(val displayName: String) {
    STEREO("Stereo"),
    SURROUND_51("5.1 Surround"),
    SURROUND_71("7.1 Surround");

    val limelightValue: Int
        get() = when (this) {
            STEREO -> (0x3 shl 16) or (2 shl 8) or 0xCA
            SURROUND_51 -> (0x3F shl 16) or (6 shl 8) or 0xCA
            SURROUND_71 -> (0x63F shl 16) or (8 shl 8) or 0xCA
        }

    val surroundAudioInfo: Int
        get() {
            val channelCount = (limelightValue shr 8) and 0xFF
            val channelMask = (limelightValue shr 16) and 0xFFFF
            return (channelMask shl 16) or channelCount
        }
}

@Serializable
enum class SystemKeyCaptureMode(val displayName: String) {
    OFF("Off"),
    FULLSCREEN_ONLY("In Fullscreen"),
    ALWAYS("Always")
}

@Serializable
data class StreamSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateMbps: Int = 30,
    val packetSize: Int = 1024,
    val codec: VideoCodec = VideoCodec.AUTO,
    val audioMode: AudioConfigurationMode = AudioConfigurationMode.STEREO,
    val enableHdr: Boolean = false,
    val fullRangeColor: Boolean = false,
    val mouseCaptureMode: Boolean = true,
    val swapWinAltKeys: Boolean = false,
    val enableVsync: Boolean = true,
    val enableFramePacing: Boolean = true,
    val optimizeGameSettings: Boolean = true,
    val quitAppOnStop: Boolean = false,
    val mouseSensitivity: Double = 1.0,
    val enableRemoteBitrate: Boolean = false,
    val remoteBitrateMbps: Int = 0,
    val enableClipboardSync: Boolean = true,
    val hideUnpairedHosts: Boolean = false,
    val muteHostAudio: Boolean = true,
    val captureSystemKeys: SystemKeyCaptureMode = SystemKeyCaptureMode.FULLSCREEN_ONLY,
    val keepScreenActive: Boolean = true,
    val mouseSensitivityX: Double = 1.0,
    val mouseSensitivityY: Double = 1.0,
    val mouseSubPixelAccumulation: Boolean = true,
    val muteAudioOnFocusLoss: Boolean = false,
    val features: ClientFeatures = ClientFeatures(),
    val controllerMask: Short = 0
) {
    fun streamBitrateKbps(isRemote: Boolean): Int {
        val normal = bitrateMbps * 1000
        if (!isRemote || !enableRemoteBitrate) return normal
        return if (remoteBitrateMbps == 0) normal / 4 else remoteBitrateMbps * 1000
    }

    val validationMessage: String?
        get() {
            if (width != 0 && height != 0 && (width !in 256..8192 || height !in 256..8192 || width % 2 != 0 || height % 2 != 0)) {
                return "Resolution must use even dimensions between 256 and 8192, or Native."
            }
            if (fps !in 1..500) return "Frame rate must be between 1 and 500 FPS."
            if (bitrateMbps !in 1..500 || remoteBitrateMbps !in 0..500) {
                return "Bitrate must be between 1 and 500 Mbps; remote bitrate can also be 0 (automatic)."
            }
            if (packetSize !in 256..1408 || packetSize % 16 != 0) {
                return "Packet size must be a multiple of 16 between 256 and 1408."
            }
            return null
        }
}
