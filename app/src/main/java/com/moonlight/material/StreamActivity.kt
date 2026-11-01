package com.moonlight.material

import android.content.pm.ActivityInfo
import android.os.Bundle
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

class StreamActivity : ComponentActivity() {

    private lateinit var sessionController: StreamSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Always lock streaming screen to landscape (supporting both landscape rotations)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        com.moonlight.material.core.utils.AppLogger.info(
            "Requested ${if (requestedSettings.isNativeResolution) "Native" else "preset"} stream mode: ${settings.width}x${settings.height}x${settings.fps}", "SESSION")

        val certManager = CertificateManager.getInstance()
        val httpClient = SunshineHTTPClient(certManager)
        sessionController = StreamSessionController(httpClient) {
            com.moonlight.material.networking.NetworkReachability(this).getCurrentState().isMetered
        }

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

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::sessionController.isInitialized) {
            if (sessionController.gamepadHandler.handleGenericMotionEvent(event)) {
                return true
            }
            if (sessionController.touchMouseHandler.handleGenericMotion(event)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
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
            if (sessionController.gamepadHandler.handleKeyEvent(event)) {
                return true
            }
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
            if (sessionController.gamepadHandler.handleKeyEvent(event)) {
                return true
            }
            if (sessionController.handleKeyEvent(event)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sessionController.isInitialized) {
            sessionController.stopSession()
        }
    }
}
