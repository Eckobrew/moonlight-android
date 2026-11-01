package com.moonlight.material

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

        // Keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        val settings = json.decodeFromString<StreamSettings>(settingsJson)

        val certManager = CertificateManager.getInstance()
        val httpClient = SunshineHTTPClient(certManager)
        sessionController = StreamSessionController(httpClient)

        setContent {
            MoonlightTheme {
                StreamingScreen(
                    host = host,
                    app = app,
                    settings = settings,
                    session = sessionController,
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (::sessionController.isInitialized && sessionController.isStreaming.value) {
                sessionController.openSessionControls()
                return true
            }
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
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
