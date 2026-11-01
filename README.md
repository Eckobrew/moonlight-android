# Moonlight for Android (Material Design)

A modern native Android game-streaming client recreating [Moonlight for macOS](http://github.com/eckobrew/moonlight-mac) with a full Material 3 UI and feature parity for PCs running [Sunshine](https://github.com/LizardByte/Sunshine) or [Apollo](https://github.com/ClassicOldSong/Apollo).

Built with **Jetpack Compose**, **Material Design 3**, **Hardware MediaCodec**, and **AudioTrack** with low latency.

---

## Features & Parity with `moonlight-mac`

| Feature | Android Material Implementation | `moonlight-mac` Reference |
| :--- | :--- | :--- |
| **Material 3 UI** | Full Material 3 theming (Dark & Light, gaming Cyan/Deep Navy palette), adaptive host & app grids, bottom sheets, segmented buttons, sliders | SwiftUI interface |
| **Video Decoding** | Hardware-accelerated `android.media.MediaCodec` (H.264, HEVC, AV1) with zero-copy `SurfaceView`, CSD/OBU parameter parsing, HDR10 static metadata InfoFrames, and refresh-rate aligned frame pacing | VideoToolbox decoder |
| **Audio Engine** | Multi-stream Opus decoding with low-latency `android.media.AudioTrack` supporting Stereo, 5.1 Surround, and 7.1 Surround, featuring dynamic buffer thresholds and underrun linear fading | AVAudioEngine / CoreAudio |
| **Display Modes** | Native screen resolution detection, 720p, 1080p, 1440p, 4K UHD, and Custom resolutions up to 8192x8192 | DisplayManager & custom resolutions |
| **Frame Rates** | 30, 60, 90, 120, 144, 240 FPS and custom frame rates | 1–500 FPS selector |
| **Bitrate Controls** | 0.5 to 150 Mbps slider with live runtime bitrate adjustment via `/bitrate` extension endpoint | Local & Remote bitrate options |
| **Cryptographic Pairing** | 5-Stage PIN pairing with SHA256 key derivation, AES-128 challenge, RSA server secret signature verification, and fail-closed mTLS certificate pinning | PairingService & HostTrust |
| **Certificate Pinning** | Host certificates pinned byte-for-byte in DER format; HTTPS connections without pinned certificate fail closed | SecTrust certificate pinning |
| **Input & Gamepads** | Physical controllers (Xbox, DualSense, Switch Pro) with multi-controller slots, haptics, face button swap (Nintendo layout) | GCController input manager |
| **Touch Controller** | On-screen virtual touch overlay: Dual analog joysticks, D-Pad, A/B/X/Y, LB/RB, LT/RT, Select/Start with adjustable opacity | Mobile touch overlay |
| **Gamepad Mouse Emulation** | Hold Start for 1 second to toggle pointer emulation with gamepad thumbstick | Gamepad mouse control |
| **Touchpad & Direct Touch** | 1-finger relative cursor, tap to click, 2-finger tap right click, 2-finger scroll with subpixel accumulation | Desktop mouse mode |
| **Physical Mouse & Keyboard** | Pointer capture (`requestPointerCapture`), relative mouse delta, discrete 120-unit wheel ticks, full Windows Virtual Key (VK) translation with left/right modifiers and extended keys | KeycodeMappings & grab |
| **Wake-on-LAN & Sleep** | Multi-destination UDP broadcast (ports 7 & 9) with serverinfo polling resolution, and two-stage single-use SleepToken host sleep | WakeOnLAN service & HostWakeConnection |
| **mDNS Host Discovery** | Local network discovery of `_nvstream._tcp` services using `NsdManager` | Bonjour discovery |
| **Performance HUD** | Minimalist high-legibility text-only overlay in the top-right corner displaying FPS, decode time, RTT, variation, live bitrate, packet loss, and host CPU/GPU metrics | PerformanceHUDView |
| **Session Controls** | Live runtime bitrate adjustment, host hardware metrics toggle, server command execution, remote monitor release, and stream termination | SessionControlsView |
| **Per-Game Settings** | Custom resolution, frame rate, bitrate, and codec overrides per individual game | PerAppSettingsSheet |
| **Box Art Management** | Asynchronous artwork download and caching via Coil with placeholder fallback | BoxArtManager & app asset |
| **Clipboard Sync** | Clipboard synchronization between Android and Sunshine/Apollo | ClipboardSyncManager |
| **Diagnostic Log Viewer** | Searchable and filterable in-app live log viewer with copy and clear actions | LogViewerSheet |
| **Localization** | Multi-language support: English, German (Deutsch), French (Français), Spanish (Español) | Bundled lproj strings |

---

## Project Structure

```
moonlight-android-material/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/moonlight/material/
│   │   │   ├── MoonlightApp.kt
│   │   │   ├── MainActivity.kt
│   │   │   ├── StreamActivity.kt
│   │   │   ├── core/
│   │   │   │   ├── models/ (Host, Application, StreamConfiguration, ClientFeatures, etc.)
│   │   │   │   ├── security/ (CryptoUtils, CertificateManager, HostTrust)
│   │   │   │   ├── state/ (AppState, AppSettingsStore, StreamSessionController)
│   │   │   │   └── utils/ (AppLogger, BoxArtManager)
│   │   │   ├── networking/ (SunshineHTTPClient, PairingService, WakeOnLAN, BonjourDiscoveryService, TCPProbe)
│   │   │   ├── stream/
│   │   │   │   ├── control/ (ControlProtocol)
│   │   │   │   ├── rtsp/ (RtspSession)
│   │   │   │   ├── video/ (MediaCodecDecoder)
│   │   │   │   ├── audio/ (AudioPlaybackEngine)
│   │   │   │   └── input/ (GamepadHandler, TouchMouseHandler, KeycodeMappings)
│   │   │   └── ui/
│   │   │       ├── theme/ (Color, Theme, Type)
│   │   │       ├── hosts/ (HostGridScreen, HostCard, AddHostDialog, HostDetailsSheet)
│   │   │       ├── apps/ (AppGridScreen, AppCard)
│   │   │       ├── pairing/ (PairingDialog)
│   │   │       ├── settings/ (SettingsScreen, PerAppSettingsDialog)
│   │   │       ├── stream/ (StreamingScreen, PerformanceHUD, SessionControlsSheet, VirtualTouchOverlay)
│   │   │       └── debug/ (LogViewerSheet)
│   │   └── res/
│   │       ├── drawable/ (vector assets: launcher foreground, background, logo, boxart placeholder)
│   │       ├── values/ (strings, colors, themes)
│   │       ├── values-de/ (German localization)
│   │       ├── values-fr/ (French localization)
│   │       └── values-es/ (Spanish localization)
│   └── build.gradle.kts
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Build & Run

### Prerequisites
- Android SDK 35 (or Android Studio Ladybug / Meerkat)
- Java 17 or Java 21
- Gradle 8.11+

### Building APK
```bash
./gradlew assembleDebug
```
Output APK is located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Running Unit Tests
```bash
./gradlew test
```
