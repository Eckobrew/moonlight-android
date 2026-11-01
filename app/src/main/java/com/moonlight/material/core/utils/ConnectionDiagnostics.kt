package com.moonlight.material.core.utils

/**
 * Port of ConnectionDiagnostics.swift: provides human-readable diagnostic messages
 * for stage failures and connection termination error codes.
 */
object ConnectionDiagnostics {

    const val ML_ERROR_GRACEFUL_TERMINATION = 0
    const val ML_ERROR_NO_VIDEO_TRAFFIC = -100
    const val ML_ERROR_NO_VIDEO_FRAME = -101
    const val ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102
    const val ML_ERROR_PROTECTED_CONTENT = -103
    const val ML_ERROR_FRAME_CONVERSION = -104

    fun ports(flags: Int): String {
        if (flags == 0) return ""
        return try {
            com.moonlight.material.bridge.MoonBridge.stringifyPortFlags(flags, ", ")
        } catch (_: Throwable) {
            "Port flags: 0x${Integer.toHexString(flags)}"
        }
    }

    fun stageFailure(stage: Int, code: Int, stageName: String? = null, portFlagsString: String? = null): String {
        val name = stageName ?: when (stage) {
            1 -> "Platform initialization"
            2 -> "Host name resolution"
            3 -> "Audio initialization"
            4 -> "RTSP handshake"
            5 -> "Control initialization"
            6 -> "Video initialization"
            7 -> "Input initialization"
            8 -> "Control connection"
            9 -> "Video connection"
            10 -> "Audio connection"
            11 -> "Input connection"
            else -> "Connection (stage $stage)"
        }
        val ports = portFlagsString ?: ""
        return "$name failed ($code)." + if (ports.isEmpty()) {
            when (stage) {
                1 -> " Restart the app. If this persists, reinstall a build compatible with this device."
                2 -> " Check the host address and this network's DNS or VPN connection."
                3, 10 -> " Check audio output on the phone and the host's audio capture device."
                4 -> " Check Sunshine is running, this device is paired, and the RTSP port is reachable."
                6, 9 -> " Check the selected codec, HDR, resolution and host video capture."
                else -> " Check that the host is reachable and paired."
            }
        } else {
            " Check firewall or forwarding for $ports."
        }
    }

    fun termination(code: Int): String? {
        return when (code) {
            ML_ERROR_GRACEFUL_TERMINATION -> null
            ML_ERROR_NO_VIDEO_TRAFFIC -> "No video arrived ($code). Check the host firewall and UDP video port forwarding."
            ML_ERROR_NO_VIDEO_FRAME -> "Video packets arrived ($code) but no complete frame could be received. Lower the bitrate or improve the network connection."
            ML_ERROR_PROTECTED_CONTENT -> "The host blocked protected content ($code). Close DRM-protected content and try again."
            ML_ERROR_UNEXPECTED_EARLY_TERMINATION -> "The host ended the stream shortly after it started ($code). Check Sunshine's capture/encoder logs and that a display is available; protected content is another possible cause."
            ML_ERROR_FRAME_CONVERSION -> "The host could not convert video ($code). Check HDR and display resolution settings."
            else -> "Connection ended ($code). Check the host and network."
        }
    }
}
