package com.moonlight.material

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Rational
import android.os.Bundle
import android.os.Build
import android.net.wifi.WifiManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.networking.SunshineHTTPClient
import com.moonlight.material.core.security.CertificateManager
import com.moonlight.material.ui.stream.StreamingScreen
import com.moonlight.material.ui.theme.MoonlightTheme
import kotlinx.serialization.json.Json
import kotlin.math.abs

class StreamActivity : ComponentActivity() {

    private lateinit var sessionController: StreamSessionController
    private var captureShortcutKey: Int? = null
    private var streamAspectRatio = Rational(16, 9)
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Always lock streaming screen to landscape (supporting both landscape rotations)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Immersive fullscreen mode
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val hostJson = intent.getStringExtra("EXTRA_HOST") ?: run { finish(); return }
        val appJson = intent.getStringExtra("EXTRA_APP") ?: run { finish(); return }
        val settingsJson = intent.getStringExtra("EXTRA_SETTINGS") ?: run { finish(); return }

        val json = Json { ignoreUnknownKeys = true }
        val host = json.decodeFromString<Host>(hostJson)
        val app = json.decodeFromString<StreamApp>(appJson)
        val requestedSettings = json.decodeFromString<StreamSettings>(settingsJson)
        val displaySize = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(displaySize)
        val settings = requestedSettings.resolveNativeResolution(displaySize.x, displaySize.y)
        if (settings.keepScreenActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val stretchOrFullScreen = settings.features.stretchToFit ||
                (displaySize.x == settings.width && displaySize.y == settings.height)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (stretchOrFullScreen) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
        }
        com.moonlight.material.core.utils.AppLogger.info(
            "Requested ${if (requestedSettings.isNativeResolution) "Native" else "preset"} stream mode: ${settings.width}x${settings.height}x${settings.fps}", "SESSION")

        val certManager = CertificateManager.getInstance()
        val httpClient = SunshineHTTPClient(certManager)
        sessionController = StreamSessionController(httpClient) {
            com.moonlight.material.networking.NetworkReachability(this).getCurrentState().isMetered
        }

        val display = windowManager.defaultDisplay
        val currentMode = display.mode
        val matchingModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
        }.filter {
            val roundedRate = kotlin.math.round(it.refreshRate).toInt().coerceAtLeast(1)
            roundedRate >= settings.fps && (roundedRate % settings.fps <= 3 || abs(it.refreshRate - settings.fps) <= 3f)
        }
        val bestMode = if (!settings.enableFramePacing) {
            matchingModes.maxByOrNull { it.refreshRate }
        } else {
            matchingModes.minByOrNull { it.refreshRate }
        } ?: display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }

        if (bestMode != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = bestMode.modeId }
        }
        if (settings.width > 0 && settings.height > 0 &&
            settings.width.toDouble() / settings.height in (1.0 / 2.39)..2.39) {
            streamAspectRatio = Rational(settings.width, settings.height)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(streamAspectRatio)
                    .setAutoEnterEnabled(true)
                    .setSeamlessResizeEnabled(true)
                    .build()
            )
        }
        sessionController.applicationContext = applicationContext
        sessionController.displayRefreshRate = { bestMode?.refreshRate ?: display.refreshRate }
        sessionController.displaySupportsHdr10 = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                display.hdrCapabilities?.supportedHdrTypes?.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10) == true
            } else {
                false
            }
        }
        sessionController.isShortcutMode = intent.getBooleanExtra("EXTRA_SHORTCUT_MODE", false)

        setContent {
            MoonlightTheme {
                StreamingScreen(
                    host = host,
                    app = app,
                    settings = settings,
                    session = sessionController,
                    onTouchModeChanged = { touchpad ->
                        val store = com.moonlight.material.core.state.AppSettingsStore(applicationContext)
                        val saved = store.loadSettings()
                        store.saveSettings(saved.copy(features = saved.features.copy(desktopMouse = touchpad)))
                    },
                    onControllerVisibilityChanged = { enabled ->
                        val store = com.moonlight.material.core.state.AppSettingsStore(applicationContext)
                        val saved = store.loadSettings()
                        store.saveSettings(saved.copy(features = saved.features.copy(touchOverlayEnabled = enabled)))
                    },
                    onFinish = {
                        sessionController.stopSession()
                        finish()
                    }
                )
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::sessionController.isInitialized && sessionController.canForwardGamepadInput &&
            sessionController.controllerHandler(event.deviceId, event.device)?.handleGenericMotionEvent(event) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) captureShortcutKey = null
        if (::sessionController.isInitialized) sessionController.updateAppFocus(hasFocus)
    }

    private fun handleStreamShortcut(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_Z && key != KeyEvent.KEYCODE_Q) return false
        if (event.action == KeyEvent.ACTION_UP && captureShortcutKey == key) {
            captureShortcutKey = null
            if (key == KeyEvent.KEYCODE_Z) {
                if (sessionController.inputCaptured.value) {
                    sessionController.sendKeyboardEvent(0x80A0.toShort(), false)
                    sessionController.sendKeyboardEvent(0x80A1.toShort(), false)
                    sessionController.sendKeyboardEvent(0x80A2.toShort(), false)
                    sessionController.sendKeyboardEvent(0x80A3.toShort(), false)
                    sessionController.sendKeyboardEvent(0x80A4.toShort(), false)
                    sessionController.sendKeyboardEvent(0x80A5.toShort(), false)
                }
                sessionController.toggleInputCapture()
            } else {
                sessionController.stopSession(forceQuitApp = false)
                finish()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed &&
            event.isAltPressed && event.isShiftPressed && !event.isMetaPressed) {
            captureShortcutKey = key
            return true
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::sessionController.isInitialized && !sessionController.localInputActive &&
            handleStreamShortcut(event)) return true
        if (::sessionController.isInitialized && sessionController.canForwardGamepadInput) {
            val handler = sessionController.controllerHandler(event.deviceId, event.device)
                ?: return super.dispatchKeyEvent(event)
            // Some pads report a key as Android Back. A physical pad must not
            // trigger the stream menu or Android's fallback from BUTTON_B to BACK.
            if (handler.handleKeyEvent(event)) return true
            if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                KeyEvent.isGamepadButton(event.keyCode) ||
                event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            (::sessionController.isInitialized && sessionController.localInputActive)) {
            return super.onKeyDown(keyCode, event)
        }
        if (::sessionController.isInitialized && sessionController.isStreaming.value) {
            if (sessionController.handleKeyEvent(event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyUp(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            (::sessionController.isInitialized && sessionController.localInputActive)) {
            return super.onKeyUp(keyCode, event)
        }
        if (::sessionController.isInitialized && sessionController.isStreaming.value) {
            if (sessionController.handleKeyEvent(event)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun enterStreamPictureInPicture(): Boolean {
        if (!::sessionController.isInitialized || sessionController.localInputActive ||
            !sessionController.isStreaming.value || isInPictureInPictureMode ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(streamAspectRatio).build()
        )
    }

    override fun onPictureInPictureRequested(): Boolean = enterStreamPictureInPicture()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterStreamPictureInPicture()
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        if (!::sessionController.isInitialized) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        try {
            wifiLock = wifi.createWifiLock(mode, "Moonlight:Stream").apply { acquire() }
        } catch (e: SecurityException) {
            com.moonlight.material.core.utils.AppLogger.warn("Could not acquire Wi-Fi lock: ${e.message}", "SESSION")
        }
    }

    override fun onStop() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sessionController.isInitialized) {
            sessionController.stopSession()
        }
    }
}
