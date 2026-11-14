package com.moonlight.material.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class DecoderPreference {
    AUTOMATIC,
    HARDWARE,
    SOFTWARE
}

@Serializable
enum class GamepadScrollStick { LEFT, RIGHT, NONE }

@Serializable
enum class VirtualDisplayMode {
    HOST_DEFAULT,
    VIRTUAL,
    PHYSICAL
}

@Serializable
data class ClientFeatures(
    val virtualDisplayMode: VirtualDisplayMode = VirtualDisplayMode.HOST_DEFAULT,
    val desktopMouse: Boolean = false,
    val swapMouseButtons: Boolean = false,
    val reverseScroll: Boolean = false,
    val swapFaceButtons: Boolean = false,
    val gamepadMouse: Boolean = true,
    val backgroundGamepad: Boolean = false,
    val persistentGamepad: Boolean = false,
    val decoder: DecoderPreference = DecoderPreference.AUTOMATIC,
    val yuv444: Boolean = false,
    val enableMdns: Boolean = true,
    val language: String = "system",
    val discordPresence: Boolean = false,
    val discordApplicationID: String = "1563323903949021204",
    // Mobile / Android specific features
    val touchOverlayEnabled: Boolean = true,
    val touchOverlayOpacity: Float = 0.65f,
    val touchHapticsEnabled: Boolean = true,
    val stretchToFit: Boolean = false,
    val gamepadScrollStick: GamepadScrollStick = GamepadScrollStick.RIGHT,
    val gamepadMouseSensitivity: Int = 100,
    val stickDeadzonePercent: Int = 7,
    val vibrateFallbackToDevice: Boolean = true,
    val gamepadMotionFallbackToDevice: Boolean = false,
    val showToggleControllerButton: Boolean = true,
    val customResolutions: List<String> = emptyList(),
    val enableLatencyToast: Boolean = false,
    val absoluteMouseMode: Boolean = false,
    val mouseNavButtons: Boolean = true
)
