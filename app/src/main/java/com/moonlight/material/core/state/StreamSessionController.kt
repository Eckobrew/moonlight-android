package com.moonlight.material.core.state

import android.view.Surface
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.PerformanceMetrics
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.core.utils.ConnectionDiagnostics
import com.moonlight.material.networking.SunshineHTTPClient
import com.moonlight.material.stream.audio.AudioPlaybackEngine
import com.moonlight.material.stream.input.GamepadHandler
import com.moonlight.material.stream.input.GamepadListener
import com.moonlight.material.stream.input.MouseEventListener
import com.moonlight.material.stream.input.TouchMouseHandler
import com.moonlight.material.stream.video.MediaCodecDecoder
import com.moonlight.material.stream.video.VideoDecoderListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class StreamSessionController(
    private val httpClient: SunshineHTTPClient
) : VideoDecoderListener, GamepadListener, MouseEventListener {

    private val TAG = "SESSION"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentStageName = MutableStateFlow("")
    val currentStageName: StateFlow<String> = _currentStageName.asStateFlow()

    private val _currentStage = MutableStateFlow(0)
    val currentStage: StateFlow<Int> = _currentStage.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    private val _connectionWarning = MutableStateFlow<String?>(null)
    val connectionWarning: StateFlow<String?> = _connectionWarning.asStateFlow()

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val _showPerformanceOverlay = MutableStateFlow(false)
    val showPerformanceOverlay: StateFlow<Boolean> = _showPerformanceOverlay.asStateFlow()

    private val _runtimeBitrateSupported = MutableStateFlow(false)
    val runtimeBitrateSupported: StateFlow<Boolean> = _runtimeBitrateSupported.asStateFlow()

    private val _runtimeBitrateKbps = MutableStateFlow(0)
    val runtimeBitrateKbps: StateFlow<Int> = _runtimeBitrateKbps.asStateFlow()

    private val _hostStatistics = MutableStateFlow<HostHardwareMetrics?>(null)
    val hostStatistics: StateFlow<HostHardwareMetrics?> = _hostStatistics.asStateFlow()

    private val _showHostStatistics = MutableStateFlow(false)
    val showHostStatistics: StateFlow<Boolean> = _showHostStatistics.asStateFlow()

    private val _hostControlMessage = MutableStateFlow<String?>(null)
    val hostControlMessage: StateFlow<String?> = _hostControlMessage.asStateFlow()

    private val _showSessionControls = MutableStateFlow(false)
    val showSessionControls: StateFlow<Boolean> = _showSessionControls.asStateFlow()

    var activeHost: Host? = null
        private set
    var activeApp: StreamApp? = null
        private set
    var activeSettings: StreamSettings? = null
        private set

    private var videoDecoder: MediaCodecDecoder? = null
    private var audioEngine: AudioPlaybackEngine? = null

    val gamepadHandler = GamepadHandler(this)
    val touchMouseHandler = TouchMouseHandler(this)

    private var sessionGeneration: Long = 0
    private var didLaunchHostApp = false
    private var preserveHostApplication = false
    var isRemoteMonitorSession = false
        private set
    var isShortcutMode = false

    var onSessionStarted: (() -> Unit)? = null
    var onSessionStopped: (() -> Unit)? = null

    val shouldQuitHostAppOnStop: Boolean
        get() = !isRemoteMonitorSession && !preserveHostApplication &&
                (if (isShortcutMode) didLaunchHostApp else activeSettings?.quitAppOnStop == true)

    fun togglePerformanceOverlay() {
        _showPerformanceOverlay.value = !_showPerformanceOverlay.value
    }

    fun toggleHostStatistics() {
        _showHostStatistics.value = !_showHostStatistics.value
        if (!_showHostStatistics.value) {
            _hostStatistics.value = null
        }
    }

    fun openSessionControls() {
        if (_isStreaming.value && _currentStageName.value == "Connected") {
            _showSessionControls.value = true
        }
    }

    fun closeSessionControls() {
        _showSessionControls.value = false
    }

    fun clearError() {
        _sessionError.value = null
    }

    fun updateStage(stage: Int, name: String) {
        if (!_isStreaming.value) return
        _currentStage.value = stage
        _currentStageName.value = name
    }

    fun markConnected() {
        if (!_isStreaming.value || _currentStageName.value == "Connected") return
        _currentStageName.value = "Connected"
    }

    fun handleStageFailed(stage: Int, code: Int) {
        if (!_isStreaming.value) return
        _sessionError.value = ConnectionDiagnostics.stageFailure(stage, code)
        stopSession()
    }

    fun handleTerminated(code: Int) {
        if (!_isStreaming.value) return
        _sessionError.value = ConnectionDiagnostics.termination(code)
        stopSession()
    }

    fun updateConnectionStatus(status: Int) {
        if (!_isStreaming.value) return
        // Matches StreamSessionController.swift:243
        _connectionWarning.value = if (status == -1) "Network quality is poor. Try lowering the bitrate." else null
    }

    fun startSession(
        host: Host,
        app: StreamApp,
        settings: StreamSettings,
        surface: Surface
    ) {
        if (_isStreaming.value) return

        val validationMsg = settings.validationMessage
        if (validationMsg != null) {
            _sessionError.value = validationMsg
            onSessionStopped?.invoke()
            return
        }

        sessionGeneration++
        val generation = sessionGeneration

        scope.launch(Dispatchers.Main) {
            try {
                activeHost = host
                activeApp = app
                activeSettings = settings
                _isStreaming.value = true
                _sessionError.value = null
                _connectionWarning.value = null
                _currentStage.value = 1
                _currentStageName.value = "Requesting session from host..."
                onSessionStarted?.invoke()

                // Generate 16-byte random key for remote input AES encryption
                // Reference: StreamSessionController.swift:328-330
                val rikey = CryptoUtils.randomBytes(16)
                val rikeyId = (1L..0xFFFFFFFFL).random().toInt()
                val rikeyHex = CryptoUtils.hexString(rikey)

                // Resolve serverinfo to ensure host is ready
                val (updatedHost, _) = httpClient.fetchServerInfo(host)
                if (!_isStreaming.value || sessionGeneration != generation) return@launch
                activeHost = updatedHost

                // Launch or Resume decision matching StreamSessionController.swift:339-355
                val sessionUrl: String?
                if (app.matchesRunning(updatedHost) || app.control?.id == 1) {
                    preserveHostApplication = true
                    sessionUrl = httpClient.resumeApp(
                        host = updatedHost,
                        settings = settings,
                        rikey = rikeyHex,
                        rikeyId = rikeyId
                    )
                } else {
                    preserveHostApplication = false
                    sessionUrl = httpClient.launchApp(
                        host = updatedHost,
                        appId = app.id,
                        appUUID = app.uuid,
                        settings = settings,
                        rikey = rikeyHex,
                        rikeyId = rikeyId
                    )
                    didLaunchHostApp = true
                }

                if (!_isStreaming.value || sessionGeneration != generation) return@launch

                _currentStage.value = 2
                _currentStageName.value = "Starting stream connection..."

                // The native Limelight connection (LiStartConnection) will be initiated here in M3.

            } catch (e: Exception) {
                AppLogger.error("Failed to start session: ${e.message}", TAG)
                _sessionError.value = e.message ?: "Connection failed"
                stopSession()
            }
        }
    }

    fun stopSession() {
        sessionGeneration++
        val hostToQuit = activeHost
        val shouldQuit = shouldQuitHostAppOnStop

        if (!_isStreaming.value) return
        _isStreaming.value = false
        onSessionStopped?.invoke()

        _currentStage.value = 0
        _currentStageName.value = ""
        _showSessionControls.value = false
        _runtimeBitrateSupported.value = false
        _hostStatistics.value = null

        videoDecoder?.stop()
        videoDecoder = null
        audioEngine?.stop()
        audioEngine = null

        if (hostToQuit != null && shouldQuit) {
            scope.launch(Dispatchers.IO) {
                try {
                    httpClient.quitApp(hostToQuit)
                } catch (e: Exception) {
                    AppLogger.error("Failed to quit app on host: ${e.message}", TAG)
                }
            }
        }
    }

    fun sendKeyboardEvent(keyCode: Short, isDown: Boolean, modifiers: Byte = 0, flags: Byte = 0) {
        // Forwarded via native LiSendKeyboardEvent2 in M6
    }

    fun applyRuntimeBitrate(mbps: Double) {
        val host = activeHost ?: return
        if (mbps <= 0.0) return
        val kbps = (mbps * 1000).toInt()
        scope.launch(Dispatchers.IO) {
            try {
                _runtimeBitrateKbps.value = kbps
                _hostControlMessage.value = "Bitrate set to ${mbps} Mbps"
            } catch (e: Exception) {
                _hostControlMessage.value = e.message
            }
        }
    }

    // VideoDecoderListener
    override fun onFrameDecoded(decodeTimeMs: Double) {
        // Will be updated from real decoder metrics
    }

    override fun onFrameDropped() {
        // Will be updated from real decoder metrics
    }

    override fun onDecoderError(error: String) {
        _sessionError.value = error
        stopSession()
    }

    // GamepadListener
    override fun onControllerStateChanged(
        controllerNumber: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    ) {
        // Forwarded via native LiSendMultiControllerEvent in M6
    }

    override fun onEmulatedMouseMove(deltaX: Short, deltaY: Short) {
        // Forwarded via native LiSendMouseMoveEvent in M6
    }

    override fun onEmulatedMouseButton(button: Byte, isDown: Boolean) {
        // Forwarded via native LiSendMouseButtonEvent in M6
    }

    override fun onEmulatedMouseScroll(scroll: Short) {
        // Forwarded via native LiSendHighResScrollEvent in M6
    }

    // MouseEventListener
    override fun onMouseMoveRelative(deltaX: Short, deltaY: Short) {
        // Forwarded via native LiSendMouseMoveEvent in M6
    }

    override fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short) {
        // Forwarded via native LiSendMousePositionEvent in M6
    }

    override fun onMouseButton(button: Byte, isDown: Boolean) {
        // Forwarded via native LiSendMouseButtonEvent in M6
    }

    override fun onMouseScroll(amount: Short) {
        // Forwarded via native LiSendHighResScrollEvent in M6
    }
}
