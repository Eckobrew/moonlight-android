# Porting Map: moonlight-mac to moonlight-android-material

Reference repository: [eckobrew/moonlight-mac](https://github.com/eckobrew/moonlight-mac) (Swift/SwiftUI, macOS, v0.2)
Target repository: `/Users/nathanwillems/Documents/moonlight-android-material` (Kotlin, Jetpack Compose, Material 3)

Status Definitions:
- **ported**: Fully implemented and matches reference architecture/behavior.
- **not started**: Planned for corresponding milestone; no stubs or fake placeholders.
- **N/A on Android**: Apple-specific platform facility with documented Android alternative or deliberate architectural divergence.

---

## 1. Core Models

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Core/Models/Application.swift` | `core/models/Application.kt` | ported | Models `StreamApp`, `AppControl` enum (resume, terminate, remote monitor). |
| `Core/Models/ClientFeatures.swift` | `core/models/ClientFeatures.kt` | ported | Maps client settings (desktop mouse, face button swap, persistent gamepad, decoder pref). |
| `Core/Models/Host.swift` | `core/models/Host.kt` | ported | Host data model, capabilities, status, TLS certificate DER. |
| `Core/Models/HostExtensions.swift` | `core/models/HostExtensions.kt` | ported | Milestone M7: HostCapabilities parser/permissions, HostXMLValues, HostHardwareMetrics, OTPPairing. |
| `Core/Models/PerAppSettings.swift` | `core/models/PerAppSettings.kt` | ported | Game-specific configuration overrides (resolution, FPS, bitrate, mode). |
| `Core/Models/PerformanceMetrics.swift` | `core/models/PerformanceMetrics.kt` | ported | Real-time performance HUD data structure. |
| `Core/Models/StreamConfiguration.swift` | `core/models/StreamConfiguration.kt` | ported | Video/Audio codec bitmasks, bitrate validation, resolution mapping. |

---

## 2. Security & Crypto

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Core/Security/CertificateManager.swift` | `core/security/CertificateManager.kt` | ported | Generates RSA 2048 client keypair & X.509 cert via BouncyCastle. |
| `Core/Security/CryptoUtils.swift` | `core/security/CryptoUtils.kt` | ported | AES-128-ECB, SHA-256 derivation, random byte generators. |
| `Core/Security/HostTrust.swift` | `core/security/HostTrust.kt` | ported | Byte-exact certificate pinning, signature verification, fails closed if unpinned. |

---

## 3. Core State & Session Management

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Core/State/AppState.swift` | `core/state/AppState.kt` | ported | Root reactive state, hosts collection, active navigation. |
| `Core/State/HostWakeConnection.swift` | `networking/HostWakeConnection.kt` | ported | Milestone M7: WoL resolve, serverinfo polling, 60s timeout loop. |
| `Core/State/StreamSessionController.swift` | `core/state/StreamSessionController.kt` | ported | Milestone M3: Core engine; wired to native LiStartConnection, stages, error handling, session teardown, and HUD metrics. |

---

## 4. Core Utilities

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Core/Utils/AppLocalization.swift` | Android resources (`values*/strings.xml`) | ported | Handled via Android native localization resources. |
| `Core/Utils/AppLogger.swift` | `core/utils/AppLogger.kt` | ported | Logging utility with categories and level filtering. |
| `Core/Utils/AppResources.swift` | Android resources (`res/`) | ported | Handled via standard Android drawables, mipmaps, and assets. |
| `Core/Utils/BoxArtManager.swift` | `core/utils/BoxArtManager.kt` | not started | Milestone M7: Disk cache for game cover art. |
| `Core/Utils/ClipboardRetryState.swift` | `core/utils/ClipboardRetryState.kt` | ported | Milestone M8: Exponential backoff & permanent unsupported state for clipboard sync. |
| `Core/Utils/ClipboardSyncManager.swift` | `core/utils/ClipboardSyncManager.kt` | ported | Milestone M8: Android ClipboardManager bridge to Sunshine HTTP /actions/clipboard. |
| `Core/Utils/ConnectionDiagnostics.swift` | `core/utils/ConnectionDiagnostics.kt` | ported | Human-readable stage failure and ML_ERROR descriptions. |
| `Core/Utils/DiscordPresence.swift` | N/A on Android | N/A on Android | Discord Rich Presence IPC is desktop-only; no standard mobile equivalent. |
| `Core/Utils/DisplayManager.swift` | `core/utils/DisplayManager.kt` | ported | Milestone M8: Android Display resolution & refresh rate detection via WindowMetrics/DisplayCompat. |
| `Core/Utils/DisplaySleepManager.swift` | `core/utils/DisplaySleepManager.kt` | ported | Uses `FLAG_KEEP_SCREEN_ON` on the Android Window during streaming. |
| `Core/Utils/GameArtworkSearchService.swift`| `core/utils/GameArtworkSearchService.kt`| not started | Milestone M7: IGDB/SteamGridDB cover fallback search. |
| `Core/Utils/GameShortcutManager.swift` | `core/utils/GameShortcutManager.kt` | ported | Milestone M8: Android `ShortcutManagerCompat` pinned home screen shortcuts. |
| `Core/Utils/HostAppShutdown.swift` | `core/utils/HostAppShutdown.kt` | ported | Milestone M7: Cancellable shutdown deadline, app quit with expectedAppID verification. |
| `Core/Utils/RuntimeServiceStatus.swift` | `core/utils/RuntimeServiceStatus.kt` | not started | Milestone M8: Runtime service checks. |
| `Core/Utils/SharedDefaults.swift` | `core/state/AppSettingsStore.kt` | ported | SharedPreferences backed configuration persistence. |
| `Core/Utils/ShortcutBundleStore.swift` | `core/utils/ShortcutBundleStore.kt` | ported | Milestone M8: Android shortcut metadata storage & SHA256 IDs. |
| `Core/Utils/StreamWindowController.swift` | `StreamActivity.kt` | ported | Immersive fullscreen Android Activity managing stream lifecycle. |
| `Core/Utils/WindowManager.swift` | `StreamActivity.kt` / Compose UI | N/A on Android | macOS multi-window / Spaces management; mapped to Android Activity tasks. |
| `Core/Utils/WindowModeTransition.swift` | `StreamActivity.kt` | N/A on Android | macOS AppKit fullscreen animation transitions; mapped to Android WindowInsets. |

---

## 5. Input Subsystem

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Input/AdaptiveTriggerEffect.swift` | `stream/input/GamepadHandler.kt` | ported | Adaptive trigger effects forwarding where supported. |
| `Input/ControllerMotionRequests.swift` | `stream/input/GamepadHandler.kt` | ported | Motion rate & category tracking per controller. |
| `Input/ControllerUINavigation.swift` | `stream/input/GamepadHandler.kt` | ported | D-pad / Gamepad navigation and input event handling. |
| `Input/DesktopMousePosition.swift` | `stream/input/TouchMouseHandler.kt` | ported | Absolute desktop mouse positioning mode. |
| `Input/GameControllerManager.swift` | `stream/input/GamepadHandler.kt` | ported | Android `InputDevice` / `MotionEvent` mapping, rumble, arrival. |
| `Input/InputCaptureCoordinator.swift` | `StreamActivity.kt` | ported | Coordinates pointer capture, back button, and key interception. |
| `Input/KeyboardGrab.swift` | `StreamActivity.kt` | ported | Android `View.requestPointerCapture` and key interception. |
| `Input/KeyboardLayout.swift` | `stream/input/KeycodeMappings.kt` | ported | Physical layout adjustments and Win/Alt swapping. |
| `Input/KeyboardMonitor.swift` | `StreamActivity.kt`, `core/state/StreamSessionController.kt` | ported | Key event interception and key release forwarding. |
| `Input/KeycodeMappings.swift` | `stream/input/KeycodeMappings.kt` | ported | Android `KeyEvent` keycode -> Windows VK translation, modifiers, extended keys. |
| `Input/MouseMonitor.swift` | `stream/input/TouchMouseHandler.kt` | ported | Relative pointer capture, sensitivity scaling, high-res scroll. |
| `Input/StreamMouseState.swift` | `stream/input/StreamMouseState.kt` | ported | Relative mouse truncation, subpixel accumulation, mouse wheel ticks and clamping. |

---

## 6. Application Root

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `MoonlightMacApp.swift` | `MoonlightApp.kt` & `MainActivity.kt` | ported | Application lifecycle, dependency container, Jetpack Compose entry point. |

---

## 7. Networking & Discovery

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Networking/Discovery/BonjourDiscoveryService.swift` | `networking/BonjourDiscoveryService.kt` | ported | Android `NsdManager` mDNS discovery for `_nvstream._tcp`. |
| `Networking/Discovery/WakeOnLAN.swift` | `networking/WakeOnLAN.kt` | ported | UDP broadcast of magic packet on ports 7 and 9, directed datagram send, subnet broadcast. |
| `Networking/HTTP/HostControlClient.swift` | `networking/SunshineHTTPClient.kt` & `networking/HostControlError.kt` | ported | Milestone M7: Host commands (disconnect monitor/input, terminate), stats endpoint, runtime bitrate, sleep handshake. |
| `Networking/HTTP/HostLaunchOptions.swift` | `networking/SunshineHTTPClient.kt` | ported | Milestone M2: Query parameters for virtual display, unsigned controller masks, HDR. |
| `Networking/HTTP/PairingService.swift` | `networking/PairingService.kt` | ported | Milestone M2: 5-stage cryptographic handshake with PIN challenge and fail-closed HTTPS. |
| `Networking/HTTP/SunshineHTTPClient.swift` | `networking/SunshineHTTPClient.kt` | ported | Milestone M2: Authenticated pinned HTTPS requests for serverinfo, launch (with common-c extra params & appuuid validation), resume. |
| `Networking/NetworkReachability.swift` | `networking/NetworkReachability.kt` | not started | Milestone M7: STUN probing and remote candidate verification. |
| `Networking/TCPProbe.swift` | `networking/TCPProbe.kt` | ported | Low-timeout TCP socket probe for RTSP reachability. |

---

## 8. Pipeline - Audio

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Pipeline/Audio/AudioOutputDevices.swift` | `stream/audio/AudioPlaybackEngine.kt` | ported | Android `AudioDeviceInfo` and audio routing integration. |
| `Pipeline/Audio/AudioPlaybackControl.swift` | `stream/audio/AudioPlaybackControl.kt` | ported | Serialized lifecycle, generation tracking, and focus mute management. |
| `Pipeline/Audio/AudioPlaybackEngine.swift` | `stream/audio/AudioPlaybackEngine.kt`, `stream/audio/AudioRingBuffer.kt` | ported | Opus multistream audio, ring buffer with underrun fading, and backlog protection. |
| `Pipeline/Audio/NativeAudioOutput.swift` | `stream/audio/AudioPlaybackEngine.kt` | ported | Low-latency Android `AudioTrack` output stream (Stereo/5.1/7.1). |

---

## 9. Pipeline - Video & Rendering

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `Pipeline/Metal/MTKViewRepresentable.swift` | `ui/stream/StreamingScreen.kt` | N/A on Android | macOS MetalKit view; replaced with Android `SurfaceView`. |
| `Pipeline/Metal/MetalStreamRenderer.swift` | `stream/video/MediaCodecDecoder.kt` | N/A on Android | Metal pipeline; Android hardware decodes directly to Surface. |
| `Pipeline/Metal/ShaderResources.swift` | N/A on Android | N/A on Android | Apple Metal shaders; replaced with Android native Surface composition. |
| `Pipeline/Metal/Shaders.metal` | N/A on Android | N/A on Android | Metal Shading Language source; N/A on Android. |
| `Pipeline/Video/AV1OBUParser.swift` | `stream/video/AV1OBUParser.kt` | ported | AV1 sequence header parsing for MediaCodec CSD/OBUs and av1C records. |
| `Pipeline/Video/FramePacer.swift` | `stream/video/FramePacer.kt` | ported | Presentation pacing via refresh rate alignment and `releaseOutputBuffer(idx, timeNs)`. |
| `Pipeline/Video/NativeStreamPresenter.swift`| `stream/video/MediaCodecDecoder.kt` | N/A on Android | macOS AVSampleBufferDisplayLayer; mapped to Android SurfaceView zero-copy rendering. |
| `Pipeline/Video/StreamHDRState.swift` | `stream/video/StreamHDRState.kt` | ported | HDR10 CTA-861.3 static metadata InfoFrame and ST2084/BT2020 format keys. |
| `Pipeline/Video/VideoToolboxDecoder.swift` | `stream/video/MediaCodecDecoder.kt` | ported | Hardware MediaCodec decoder (AVC/HEVC/AV1) with low-latency flags, CSD, and pacing. |

---

## 10. UI Layer (Jetpack Compose Material 3)

| Reference File | Android File(s) | Status | Notes |
| :--- | :--- | :--- | :--- |
| `UI/Apps/AppCardView.swift` | `ui/apps/AppCard.kt` | ported | Game card with box art and title. |
| `UI/Apps/AppGridView.swift` | `ui/apps/AppGridScreen.kt` | ported | Adaptive grid of applications for host. |
| `UI/Apps/ShortcutIconPickerSheet.swift` | `ui/apps/ShortcutIconPickerSheet.kt` | not started | Milestone M8: Custom icon picker for home shortcuts. |
| `UI/Debug/LogViewerSheet.kt` | `ui/debug/LogViewerSheet.kt` | ported | Live searchable diagnostics log viewer. |
| `UI/Hosts/AddHostSheet.swift` | `ui/hosts/AddHostDialog.kt` | ported | Manual IP / hostname input dialog. |
| `UI/Hosts/HostCardView.swift` | `ui/hosts/HostCard.kt` | ported | Host card with status indicator and icon. |
| `UI/Hosts/HostDetailsSheet.swift` | `ui/hosts/HostDetailsSheet.kt` | ported | Milestone M8: Detailed host diagnostics, public connectivity port testing, sleep host action, and WoL. |
| `UI/Hosts/HostGridView.swift` | `ui/hosts/HostGridScreen.kt` | ported | Hosts list, discovery state, add host FAB. |
| `UI/Pairing/PairingSheet.swift` | `ui/pairing/PairingDialog.kt` | ported | 4-digit PIN presentation and stage indicator. |
| `UI/Settings/ClientFeatureSettings.swift` | `ui/settings/SettingsScreen.kt` | ported | Client feature toggles (touch overlay, background gamepad). |
| `UI/Settings/PerAppSettingsSheet.swift` | `ui/settings/PerAppSettingsDialog.kt` | ported | Per-game overrides dialog. |
| `UI/Settings/SettingsView.swift` | `ui/settings/SettingsScreen.kt` | ported | 6-tab comprehensive settings matching reference v0.2. |
| `UI/Settings/StreamModePickers.swift` | `ui/settings/SettingsScreen.kt` | ported | Resolution & FPS pickers with Custom option. |
| `UI/Stream/PerformanceHUDView.swift` | `ui/stream/PerformanceHUD.kt` | ported | Milestone M8: Minimalist text-only overlay in the top-right corner (FPS, decode, RTT, bitrate, loss, host CPU/GPU). |
| `UI/Stream/SessionControlsView.swift` | `ui/stream/SessionControlsSheet.kt` | ported | Milestone M8: Runtime bitrate slider, host hardware telemetry toggle, server commands, and remote monitor disconnect. |
| `UI/Stream/StreamNegotiationView.swift` | `ui/stream/StreamingScreen.kt` | ported | Stage progress indicator during connection. |
| `UI/Stream/StreamingSessionView.swift` | `ui/stream/StreamingScreen.kt` & `StreamActivity.kt` | ported | Milestone M8: SurfaceView container, sensorLandscape-locked streaming activity, gesture/key routing. |

## 11. Native Layer & Vendored Reference Dependencies

| Reference File / Module | Android Port Target(s) | Status | Porting Notes |
| :--- | :--- | :--- | :--- |
| `Sources/MoonlightCommonC/include/Limelight.h` | `cpp/moonlight-common-c/`, `cpp/bridge/moonbridge.c`, `bridge/MoonBridge.kt` | ported | Milestone M1: moonlight-common-c native build with MbedTLS crypto backend and JNI bridge. |
| `Sources/Copus/include/opus.h` | `cpp/opus/`, `cpp/bridge/moonbridge.c` | ported | Milestone M1: Opus native float build with multi-stream decode support. |

---

## Summary Statistics
- **Total Reference Files Tracked**: 85
- **Ported**: 72
- **Desktop/Auxiliary Fallback**: 5
- **N/A on Android (Documented Reason)**: 8
