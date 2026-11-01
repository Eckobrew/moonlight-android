# User test checklist

1. **Input:** In a stream, switch between Touchscreen and Touchpad. Check native taps, dragging, two fingers, and tapping a PC text field while the phone keyboard is open. Type, use Backspace/Enter, and try emoji. No local text box should appear.
2. **Controls:** Check Keyboard / Settings / Controller order and original circular styling. The menu should have matching blue buttons, full-width touch-mode choices, Performance HUD below them, and centred Disconnect/Quit at the bottom.
3. **Controllers:** Toggle the virtual gamepad from the toolbar and menu. Connect a USB or Bluetooth gamepad: the overlay should hide; disconnect it: the saved overlay preference should return. Check no virtual buttons remain held.
4. **Exit:** Disconnect should leave the host game running and allow Resume. Quit Game should request confirmation, stop the host game, and return to the browser. Check the host status refreshes.
5. **Display:** Select 1080p and confirm black bars rather than stretching. Select Native and check the phone's landscape dimensions. Check the separate 144 FPS row and per-game overrides.
6. **HUD:** Compare Received, Decoded and Rendered FPS while moving an animated scene. Check updates about four times per second, sensible Decode + queue values, and FPS falling when frames stop. Requested FPS is a target, not a measured result.
7. **Browser:** Check box art, refresh without losing apps, Settings beside Refresh, and the single bottom-right host status badge.
8. **Remote:** Open Host Details and Discover Remote Address. Check Active/Remote addresses and verification status. With working port forwarding, try a different network and confirm remote fallback. Unverified candidates must not be used as routes.
9. **Bitrate:** Enable a custom remote bitrate. Check it is selected for a verified remote route or metered network, while an unmetered local connection uses the normal bitrate. Host Details and the streaming HUD should agree with the selected profile/target.
10. **Branding:** Check the official Moonlight launcher icon, the new package identity, and the host-screen title without the old moon icon.
