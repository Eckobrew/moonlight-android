# Android connection and browser audit

Audited 1 November 2026 against the reported intermittent connection, disappearing applications, and host-screen problems. This is a focused audit of those paths, not a complete validation of every ported feature.

## Findings and fixes

| Severity | Finding | Change |
| --- | --- | --- |
| High | Authenticated clients cached an SSLContext. The Pixel 8a intermittently reported `TLSV1_ALERT_INTERNAL_ERROR` during subsequent requests; launch also timed out before RTSP. | Create a fresh pinned TLS context for each authenticated request, disable idle connection reuse, and close HTTP connections. Keep certificate pinning and disabled redirects. |
| High | Android's default client-certificate selection can omit a self-signed paired identity when the requested issuer list differs. | Explicitly select the paired RSA client identity. Added a mutual-TLS handshake regression with a different requested issuer. This is a compatibility hardening fix; the live intermittent failure persisted until TLS context reuse was removed. |
| High | Failed app refresh replaced the catalogue with an empty list, presenting a network/authentication failure as “No applications found.” XML errors and malformed app lists could also be treated as empty success. | Preserve existing apps on failure, show the error, validate protocol status and XML structure, accept genuinely empty successful catalogues. |
| High | HEVC, AV1, HDR and 4:4:4 codec masks did not match the bundled `Limelight.h`; old tests repeated the wrong constants. | Correct masks and regression expectations. Also correct `STREAM_CFG_AUTO` from 0 to 2. |
| Medium | Host refresh replaced the entire list after asynchronous probes, potentially deleting newly discovered hosts, restoring deleted hosts, or overwriting pairing/actions. | Apply results per host only if that host has not changed while its probe was in flight. Prevent overlapping refreshes. |
| Medium | Late app results could populate a different selected host; selected-host/details objects stayed stale after host updates. | Guard app loads by selected host, cancel on navigation, synchronize selected host/details with updates. Keep host IDs and existing credentials when discovery/manual addition resolves to an existing host. |
| Medium | Server info omitted server ID and running-app metadata, so resume detection and streaming badges were incorrect. Paired hosts could be labelled online based on an unauthenticated HTTP fallback. | Parse server ID/current app/UUID/last seen, verify saved server identity, and require authenticated server info for securely paired hosts. |
| Medium | Pairing dialog cancellation only hid the UI; host action results were not displayed, and quit/unpair failures could be ignored. | Cancel the pairing coroutine, display action results, handle action failures, and validate XML status for quit/unpair. |
| Medium | Remote-input key IDs were serialized as signed integers after converting random unsigned 32-bit values to Kotlin Int. | Serialize the wire value as unsigned decimal. |
| Low | System Back did not navigate from apps to hosts; app refresh hid the grid while loading; custom host port handling retained the default HTTPS port until discovery succeeded. | Add Back handling, keep existing apps visible while refreshing, derive the initial HTTPS port from the custom HTTP port, and validate manual ports. |

## Validation

- `./gradlew testDebugUnitTest assembleDebug` passes: **97 tests, 0 failures, 0 errors**.
- Added coverage for TLS-context isolation, client identity with nonmatching requested issuers, correct native codec masks, protocol-error app lists, malformed catalogues, and valid empty catalogues.
- Pixel 8a: reproduced app-refresh TLS failures before the TLS-context change. Forcing TLS 1.2 alone did not solve them; the final implementation retains normal TLS version negotiation.
- Pixel 8a with fresh TLS contexts: catalogue loaded, Desktop launch completed the RTSP handshake, the HEVC decoder started at 1920×1080/60, audio playback initialized, and the first video packet arrived. Live logcat confirmed these stages.
- This live session does not establish long-duration stability, every application's launch behavior, every codec/device combination, or WAN connectivity. Coroutine races were reviewed and guarded in code; they do not yet have an Android UI stress test.
- Existing local edits to `app/build.gradle.kts` and `app/src/main/cpp/CMakeLists.txt` were preserved.

## References

- [Original macOS HTTP implementation](https://github.com/eckobrew/moonlight-mac/blob/main/Sources/MoonlightMac/Networking/HTTP/SunshineHTTPClient.swift)
- [Official Moonlight Android HTTP client](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java), particularly `performAndroidTlsHack`, per-connection TLS context creation, client identity selection, and connection-pool configuration.
- Codec/stream-mode values were checked against the bundled `app/src/main/cpp/moonlight-common-c/src/Limelight.h`.

## Follow-up: input, display, routes, identity and HUD

- Native touchscreen forwarding follows the fork's [native-touch commit](https://github.com/Eckobrew/moonlight-android/commit/02b3d2dce36b9fd686cb8b593595ca3882450606). A Compose gesture target now routes input above the video surface; relying on SurfaceView touch interop beneath overlays did not reliably deliver events. Touchscreen and Touchpad can be changed live and saved. The user subsequently confirmed both modes work.
- The phone IME types directly to the PC. Removed the local text box and special-key strip. Composition remains local until committed; deletion and proper UTF-8 encoding are covered by unit tests.
- Restored the original circular control style with Keyboard, Settings, Controller in that order. Added a menu gamepad toggle, physical USB/Bluetooth controller auto-hide, shared blue quick-action styles, and centred Disconnect/Quit actions at the menu bottom. Device checks confirmed Disconnect returns to MainActivity and Quit resets the host's currentgame to zero.
- Game artwork now uses authenticated downloads and a versioned local cache; on-device screenshots confirmed visible posters.
- Added Native resolution, 144 FPS on its own row, and aspect-ratio-preserving video with black bars. Added Settings next to Refresh on the games screen. Host cards now have one status badge at bottom right and no decorative logo beside the screen title.
- Host Details shows Active and Remote addresses, remote verification state, effective bitrate and metering. STUN supplies a candidate when a directly connected local host omits ExternalIP. A candidate is displayed separately from a verified usable route; failed checks never overwrite a saved route. Off-LAN/port-forwarded operation still needs user verification.
- Android application ID is `com.eckobrew.moonlight_android` because package identifiers cannot contain a hyphen. Launcher assets come from [official Moonlight Android](https://github.com/moonlight-stream/moonlight-android/tree/master/app/src/main/res).
- HUD FPS now separates received pictures, decoder output, and actual MediaCodec frame-rendered callbacks. Decode-plus-queue time uses matched monotonic submission/output timestamps. Rolling one-second samples refresh every 250ms and expire on stalls. The bitrate label explicitly says Target; unknown packet loss is no longer displayed as zero.
- Validation: a full pre-HUD unit run passed 106 tests; the three new clock-driven HUD tests pass separately. The final debug APK builds. Further interactive device tests were stopped at the user's request.

## Latest interface and automatic-remote refinements

The stream menu now opens through Android Back only; floating top buttons are removed. The HUD keeps rendered FPS, decode timing, RTT and resolution/codec without received/decoded rates or target figures. Quick actions use the previous keyboard button style without spacer gaps. Closing the Android IME also clears local keyboard input mode.

Remote configuration is automatic only. Default-network changes and successful pairing trigger discovery. A candidate must pass pinned WAN HTTPS/server-ID checks plus the corresponding HTTP server-ID check before being saved; LAN verification therefore exercises router hairpin access. Unverified candidates stay distinct from usable routes. Saved remote address/port metadata survives rediscovery merges and is persisted in saved_hosts. Manual remote address configuration has been removed.

## Native Resume and exact original menu style

Resume calculated the requested mode but did not send it. Its request now includes the resolved dimensions/FPS, additionalStates and SOPS, matching the reference Android request. Native resolution therefore reaches the host on both launch and resume.

All remote discovery now requires both a default Wi-Fi/Ethernet connection and a directly connected physical host subnet before querying WAN information or considering any candidate. Cellular/VPN and off-subnet routes only use previously saved verified endpoints.

The menu button definitions were compared directly against pre-audit commit 21eb74e. Natural-height cyan outlined keyboard/quick-action buttons and the filled dark Disconnect/red Quit buttons are restored, with centred exit labels and no added spacer gaps. The floating toolbar remains removed and the HUD omits received/decoded/target figures. The final APK builds successfully; further device interaction checks remain with the user.
