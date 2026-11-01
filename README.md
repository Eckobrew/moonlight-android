# Moonlight for Android — Material

Stream games and your Windows desktop from your PC to an Android phone or tablet, with a Material 3 interface.

This is Eckobrew's Android client inspired by [Moonlight for macOS](https://github.com/eckobrew/moonlight-mac). It uses Moonlight's streaming core and works with [Sunshine](https://github.com/LizardByte/Sunshine) and compatible hosts such as [Apollo](https://github.com/ClassicOldSong/Apollo).

**Development preview:** the interface and streaming features are still being refined. Available options depend on your phone, controller, network and host software.

## What you need

- Android 8.0 or newer.
- A PC running Sunshine or a compatible streaming host, with at least one application or Desktop configured.
- A good connection between the PC and your Android device. Start with both on the same local network; wired Ethernet for the PC is helpful.

## Install

Download a development APK from [Android Build](https://github.com/eckobrew/moonlight-android/actions/workflows/android.yml):

1. Open the latest successful build of `master`.
2. Download **Moonlight-Material-APK** from its artifacts. Sign in to GitHub if prompted.
3. Extract the ZIP and open `app-debug.apk` on your Android device.
4. Allow installation from your browser or file manager when Android asks.

This Material client uses the package ID `com.eckobrew.moonlight_android`. Older builds of the previous client in this repository's Releases are a different app; use the Material build above.

## Connect to your PC

1. Open Sunshine on your PC and make sure it is running.
2. Open Moonlight. PCs on the same network should appear automatically.
3. If your PC is missing, use **+** to enter its local IP address or hostname.
4. Select the PC and start pairing. Enter the PIN shown on your phone in Sunshine's pairing page.
5. Open the paired PC and choose Desktop or a game.

If a PC is shown as offline, check its address and firewall, or wake it using Wake-on-LAN if supported and configured.

## While streaming

Use the **Android back gesture or Back button** to open the stream menu.

- **Show On-Screen Keyboard:** type directly on the PC using your phone's keyboard.
- **Touchscreen:** forward native touch gestures to a supported host. Other hosts fall back to mouse input.
- **Touchpad:** move the mouse pointer with relative finger movements.
- **Show On-screen Gamepad:** toggle virtual controls. They hide automatically when a USB or Bluetooth gamepad is connected.
- **Performance HUD:** show rendered FPS, resolution/codec, decode timing and network latency.
- **Disconnect Stream:** return to Moonlight and leave the host application running for Resume.
- **Quit Game:** stop the application on the PC after confirmation.

The menu also contains bitrate controls and any additional commands supported by your host. Live bitrate changes require host support.

## Picture and performance settings

Open Settings from the PC list or beside Refresh on the games screen.

- Choose 720p, 1080p, 1440p, 4K, or **Native** to request your phone's landscape resolution. Video keeps its aspect ratio instead of stretching.
- Choose a frame rate, including a separate **144 FPS** option. Actual rendered FPS depends on the display, decoder, host and network.
- Start with H.264 or Auto. HEVC, AV1, HDR and advanced colour modes need compatible hardware and host support.
- Lower the bitrate if you see stuttering, latency or network-quality warnings.
- Use the settings icon on a game for per-game overrides.

Native requests the stream size. Changing the Windows desktop itself also depends on the host's display configuration; a virtual display may be needed to match a phone's aspect ratio.

## Add a game to your home screen

Select the shortcut icon on a game card to open the artwork picker:

1. Use the host's box art, search online, or browse for an image on your phone.
2. Preview a squircle, circle, logo cutout or box-art icon.
3. Choose crop/fit and a blurred, dark, black or transparent backdrop where available.
4. Tap **Add to Home Screen** and confirm with your launcher.

Steam artwork works without an API key. An optional SteamGridDB key enables additional community artwork. Your launcher may apply its own icon mask.

Shortcuts resolve the current saved PC, application and settings when opened. They can wake a configured sleeping PC, use a verified remote route, and apply current per-game settings. Recreating the same host/game shortcut updates its icon and target instead of assigning a new identity.

## Remote streaming

Set up the host and router for remote streaming using your host software's networking instructions.

While the phone is on the PC's physical local Wi-Fi/Ethernet subnet, Moonlight automatically discovers a remote-address candidate and checks it against the paired host. It saves a route only after the certificate and server identity match and the corresponding control ports respond. **Host Details** shows Active Address, Remote Address and verification status.

- Discovery does not infer the host's address from the phone's cellular or VPN public IP.
- Saved verified routes persist and are used as fallback when the local address is unavailable.
- Remote addresses are discovered automatically; there are no manual remote-address fields.
- Verification from the local network needs router NAT loopback/hairpin support. An unverified candidate is displayed but is not used as a trusted route.
- Enable **Custom Bitrate for Remote / Metered Connections** to use a different bitrate away from an unmetered local connection.

CGNAT, missing port forwarding or a router without hairpin support can prevent automatic verification.

## Troubleshooting

**The PC is missing or offline**

Check Sunshine is running, the PC is awake, and both devices are on the intended network. Try adding the local address manually.

**Pairing or certificate error**

Check the PIN and the PC's identity. If Sunshine was reinstalled or its certificate changed, pair with the PC again.

**Apps disappear after refreshing**

A failed refresh should keep the previous catalogue and show an error. Retry after checking connectivity; include the error if reporting a bug.

**A stream fails to start or ends unexpectedly**

Read the error's stage, native code and port hints. Open the app's **Logs** screen: it includes `moonlight-common-c` messages as well as client logs. No-video errors usually need network/firewall checks; capture, codec or HDR errors may need host/display changes.

**An APK update will not install**

Check you are updating the same package from the same signing source. Locally built and CI development APKs can have different signing keys.

When [reporting an issue](https://github.com/eckobrew/moonlight-android/issues), include your phone/Android version, host software, local or remote connection, stream settings, exact error and relevant logs. Remove any private information before posting logs.

## Build from source

Open the project in Android Studio, or use JDK 17+, Android SDK 35, NDK `27.2.12479018` and CMake `3.22.1`:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Native streaming dependencies are included in the repository.

## Credits and licence

Based on [Moonlight](https://moonlight-stream.org), [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c), and [Eckobrew's moonlight-mac](https://github.com/eckobrew/moonlight-mac). The launcher icon comes from official Moonlight Android.

See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md) for licensing and bundled dependency notices. This is a community project, not an official Moonlight release.
