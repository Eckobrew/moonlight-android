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

    fun stageFailure(stage: Int, code: Int, stageName: String? = null, portFlagsString: String? = null): String {
        val name = stageName ?: "Connection (stage $stage)"
        val ports = portFlagsString ?: ""
        return "$name failed ($code)." + if (ports.isEmpty()) {
            " Check that the host is reachable and paired."
        } else {
            " Check firewall or forwarding for $ports."
        }
    }

    fun termination(code: Int): String? {
        return when (code) {
            ML_ERROR_GRACEFUL_TERMINATION -> null
            ML_ERROR_NO_VIDEO_TRAFFIC -> "No video arrived. Check the host firewall and UDP video port forwarding."
            ML_ERROR_NO_VIDEO_FRAME -> "Video packets arrived but no complete frame could be received. Lower the bitrate or improve the network connection."
            ML_ERROR_PROTECTED_CONTENT, ML_ERROR_UNEXPECTED_EARLY_TERMINATION -> "The host stopped capture. Close DRM-protected content and try again."
            ML_ERROR_FRAME_CONVERSION -> "The host could not convert video. Check HDR and display resolution settings."
            else -> "Connection ended ($code). Check the host and network."
        }
    }
}
