package com.moonlight.material.core.state

import android.view.Surface
import com.moonlight.material.bridge.MoonBridge
import com.moonlight.material.core.models.AppControl
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.PerformanceMetrics
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.core.utils.ConnectionDiagnostics
import com.moonlight.material.networking.SunshineHTTPClient
import com.moonlight.material.networking.TCPProbe
import com.moonlight.material.stream.audio.AudioPlaybackEngine
import com.moonlight.material.stream.input.GamepadHandler
import com.moonlight.material.stream.input.GamepadListener
import com.moonlight.material.stream.input.KeycodeMappings
import com.moonlight.material.stream.input.MouseEventListener
import com.moonlight.material.stream.input.TouchMouseHandler
import com.moonlight.material.stream.video.MediaCodecDecoder
import com.moonlight.material.stream.video.VideoDecoderListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamSessionController(
    private val httpClient: SunshineHTTPClient,
    private val isMeteredNetwork: () -> Boolean = { false }
) : MoonBridge.ConnectionListener,
    VideoDecoderListener,
    GamepadListener,
    MouseEventListener {

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

    private val _bitrateRequestInProgress = MutableStateFlow(false)
    val bitrateRequestInProgress: StateFlow<Boolean> = _bitrateRequestInProgress.asStateFlow()

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

    var currentSettings: StreamSettings? = null
        private set

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

    @androidx.annotation.VisibleForTesting
    internal fun setStreamingForTesting(streaming: Boolean) {
        _isStreaming.value = streaming
    }

    private var streamJob: Job? = null
    private var stoppingJob: Job? = null
    private var metricsJob: Job? = null

    // Real negotiated stream parameters reported by drSetup callback
    private var negotiatedWidth: Int? = null
    private var negotiatedHeight: Int? = null
    private var negotiatedFps: Int? = null
    private var negotiatedCodec: String? = null

    fun togglePerformanceOverlay() {
        _showPerformanceOverlay.value = !_showPerformanceOverlay.value
    }

    fun toggleHostStatistics() {
        _showHostStatistics.value = !_showHostStatistics.value
        if (!_showHostStatistics.value) {
            _hostStatistics.value = null
        }
    }

    private val _touchpadMode = MutableStateFlow(false)
    val touchpadMode: StateFlow<Boolean> = _touchpadMode.asStateFlow()

    fun setTouchpadMode(enabled: Boolean) {
        touchMouseHandler.reset()
        touchMouseHandler.isDesktopMouseMode = enabled
        _touchpadMode.value = enabled
    }

    var localInputActive: Boolean = false

    fun openSessionControls() {
        if (_isStreaming.value) {
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
        startConnectedServices()
    }

    fun handleStageFailed(stage: Int, code: Int, customMessage: String? = null) {
        if (!_isStreaming.value) return
        val portFlags = try { MoonBridge.getPortFlagsFromStage(stage) } catch (_: Throwable) { 0 }
        val portStr = if (portFlags != 0) try { MoonBridge.stringifyPortFlags(portFlags, ", ") } catch (_: Throwable) { null } else null
        val stageName = try { MoonBridge.getStageName(stage) } catch (_: Throwable) { null }
        _sessionError.value = customMessage ?: ConnectionDiagnostics.stageFailure(stage, code, stageName, portStr)
        stopSession()
    }

    fun handleTerminated(code: Int, customMessage: String? = null) {
        if (!_isStreaming.value) return
        _sessionError.value = customMessage ?: ConnectionDiagnostics.termination(code)
        stopSession()
    }

    fun updateConnectionStatus(status: Int) {
        if (!_isStreaming.value) return
        // Matches StreamSessionController.swift:243 (CONN_STATUS_POOR = 1)
        _connectionWarning.value = if (status == 1) "Network quality is poor. Try lowering the bitrate." else null
    }

    fun updateAppFocus(focused: Boolean) {
        audioEngine?.updateFocus(focused)
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
                // Ensure any previous session teardown has completed before starting another
                stoppingJob?.join()
                if (sessionGeneration != generation) return@launch

                activeHost = host
                activeApp = app
                activeSettings = settings
                _isStreaming.value = true
                _sessionError.value = null
                _connectionWarning.value = null
                _currentStage.value = 1
                _currentStageName.value = "Requesting session from host..."
                negotiatedWidth = null
                negotiatedHeight = null
                negotiatedFps = null
                negotiatedCodec = null
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

                // Launch or Resume decision matching StreamSessionController.swift:301, 339, 359
                preserveHostApplication = app.control == AppControl.RESUME
                val sessionUrl: String?
                if (app.matchesRunning(updatedHost) || app.control == AppControl.RESUME) {
                    sessionUrl = httpClient.resumeApp(
                        host = updatedHost,
                        settings = settings,
                        rikey = rikeyHex,
                        rikeyId = rikeyId
                    )
                } else {
                    didLaunchHostApp = !app.matchesRunning(updatedHost) && !isRemoteMonitorSession
                    sessionUrl = httpClient.launchApp(
                        host = updatedHost,
                        appId = app.id,
                        appUUID = app.uuid,
                        settings = settings,
                        rikey = rikeyHex,
                        rikeyId = rikeyId
                    )
                }

                if (!_isStreaming.value || sessionGeneration != generation) return@launch

                currentSettings = settings
                gamepadHandler.swapFaceButtons = settings.features.swapFaceButtons
                gamepadHandler.gamepadMouseEnabled = settings.features.gamepadMouse
                setTouchpadMode(settings.features.desktopMouse)
                touchMouseHandler.mouseSensitivityX = settings.mouseSensitivityX * settings.mouseSensitivity
                touchMouseHandler.mouseSensitivityY = settings.mouseSensitivityY * settings.mouseSensitivity
                touchMouseHandler.mouseSubPixelAccumulation = settings.mouseSubPixelAccumulation
                touchMouseHandler.swapMouseButtons = settings.features.swapMouseButtons
                touchMouseHandler.reverseScroll = settings.features.reverseScroll
                touchMouseHandler.reset()

                // Create hardware video decoder and audio playback engine
                videoDecoder = MediaCodecDecoder(
                    surface = surface,
                    streamWidth = settings.width,
                    streamHeight = settings.height,
                    streamFps = settings.fps,
                    enablePacing = false,
                    listener = this@StreamSessionController
                )
                audioEngine = AudioPlaybackEngine(muteOnFocusLoss = settings.muteAudioOnFocusLoss)

                // Register native bridge callbacks
                MoonBridge.connectionListener = this@StreamSessionController
                MoonBridge.videoDecoderListener = videoBridgeListener
                MoonBridge.audioRendererListener = audioBridgeListener

                // Wait up to 3s for host RTSP port to accept connections (StreamSessionController.swift:401, 509-534)
                waitForRtspPortReady(updatedHost, sessionUrl)
                if (!_isStreaming.value || sessionGeneration != generation) return@launch

                // Launch Limelight connection on background thread (LiStartConnection blocks)
                startLimelightConnection(
                    host = updatedHost,
                    sessionUrl = sessionUrl,
                    settings = settings,
                    rikey = rikey,
                    rikeyId = rikeyId,
                    generation = generation
                )

            } catch (e: Exception) {
                AppLogger.error("Failed to start session: ${e.message}", TAG)
                _sessionError.value = e.message ?: "Connection failed"
                stopSession()
            }
        }
    }

    private suspend fun waitForRtspPortReady(host: Host, sessionUrl: String?, timeoutSeconds: Double = 3.0) {
        var port = 48010
        if (!sessionUrl.isNullOrEmpty()) {
            try {
                val uri = java.net.URI(sessionUrl)
                if (uri.port > 0) {
                    port = uri.port
                }
            } catch (_: Exception) {}
        }

        val address = host.effectiveAddress
        val start = System.currentTimeMillis()
        val deadline = start + (timeoutSeconds * 1000).toLong()

        while (System.currentTimeMillis() < deadline) {
            if (!_isStreaming.value) return
            val reachable = TCPProbe.canConnect(address, port, 1000)
            if (reachable) {
                val elapsed = System.currentTimeMillis() - start
                AppLogger.info("RTSP port $port is ready on $address (waited ${elapsed}ms)", TAG)
                return
            }
            delay(150)
        }
        AppLogger.info("Proceeding to RTSP handshake for $address:$port", TAG)
    }

    private fun startLimelightConnection(
        host: Host,
        sessionUrl: String?,
        settings: StreamSettings,
        rikey: ByteArray,
        rikeyId: Int,
        generation: Long
    ) {
        val chosenBitrateKbps = settings.streamBitrateKbps(isRemote = host.isRemoteConnection || isMeteredNetwork())
        _runtimeBitrateKbps.value = chosenBitrateKbps

        val riAesKey = rikey
        val riAesIv = ByteArray(16)
        ByteBuffer.wrap(riAesIv).order(ByteOrder.BIG_ENDIAN).putInt(rikeyId)

        val videoFormatMask = settings.codec.limelightMask(settings.enableHdr, settings.features.yuv444)
        val videoCapabilities = 0x1 // CAPABILITY_DIRECT_SUBMIT

        val colorSpace = 2 // COLORSPACE_REC_709
        val colorRange = if (settings.fullRangeColor) 1 else 0

        streamJob = scope.launch(Dispatchers.IO) {
            val result = try {
                MoonBridge.startConnection(
                    address = host.effectiveAddress,
                    appVersion = host.appVersion,
                    gfeVersion = host.gfeVersion.ifEmpty { null },
                    rtspSessionUrl = sessionUrl,
                    serverCodecModeSupport = host.serverCodecModeSupport,
                    width = settings.width,
                    height = settings.height,
                    fps = settings.fps,
                    bitrate = chosenBitrateKbps,
                    packetSize = settings.packetSize,
                    streamingRemotely = if (host.isRemoteConnection) 1 else 2, // REMOTE or AUTO
                    audioConfiguration = settings.audioMode.limelightValue,
                    supportedVideoFormats = videoFormatMask,
                    clientRefreshRateX100 = 6000,
                    riAesKey = riAesKey,
                    riAesIv = riAesIv,
                    videoCapabilities = videoCapabilities,
                    colorSpace = colorSpace,
                    colorRange = colorRange
                )
            } catch (t: Throwable) {
                AppLogger.error("LiStartConnection threw: ${t.message}", TAG)
                -1
            }

            withContext(Dispatchers.Main) {
                if (_isStreaming.value && sessionGeneration == generation) {
                    if (result != 0 && _sessionError.value == null) {
                        _sessionError.value = "Connection failed with code $result"
                        stopSession()
                    }
                }
            }
        }
    }

    private fun startConnectedServices() {
        stopMetricsPolling()
        startMetricsPolling()
    }

    private fun startMetricsPolling() {
        metricsJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isStreaming.value) {
                val rttLong = try { MoonBridge.getEstimatedRttInfo() } catch (_: Throwable) { -1L }
                val rttMs = if (rttLong >= 0) (rttLong ushr 32).toDouble() else 0.0
                val rttVarMs = if (rttLong >= 0) (rttLong and 0xFFFFFFFFL).toDouble() else 0.0

                val video = videoDecoder?.performance?.snapshot()
                val settings = activeSettings
                val currentWidth = negotiatedWidth ?: settings?.width ?: 1920
                val currentHeight = negotiatedHeight ?: settings?.height ?: 1080
                val currentCodec = negotiatedCodec ?: "H.264"
                val targetFps = negotiatedFps ?: settings?.fps ?: 60

                withContext(Dispatchers.Main) {
                    _metrics.value = PerformanceMetrics(
                        currentFps = video?.renderedFps ?: 0.0,
                        receivedFps = video?.receivedFps ?: 0.0,
                        decodedFps = video?.decodedFps ?: 0.0,
                        decoderDroppedFrames = video?.decoderDroppedFrames ?: 0,
                        targetFps = targetFps,
                        decodeTimeMs = video?.decodeTimeMs ?: 0.0,
                        roundTripTimeMs = rttMs,
                        roundTripTimeVarianceMs = rttVarMs,
                        bitrateMbps = _runtimeBitrateKbps.value / 1000.0,
                        packetLossPercentage = 0.0,
                        networkDroppedFrames = video?.networkDroppedFrames ?: 0,
                        currentCodec = currentCodec,
                        resolution = "${currentWidth}x${currentHeight}",
                        vsyncEnabled = settings?.enableVsync ?: true,
                        pacingEnabled = settings?.enableFramePacing ?: true
                    )
                }
                delay(250)
            }
        }
    }

    private fun stopMetricsPolling() {
        metricsJob?.cancel()
        metricsJob = null
    }

    fun stopSession(forceQuitApp: Boolean? = null) {
        touchMouseHandler.reset()
        sessionGeneration++
        val hostToQuit = activeHost
        val shouldQuit = forceQuitApp ?: shouldQuitHostAppOnStop

        if (!_isStreaming.value) return
        _isStreaming.value = false
        onSessionStopped?.invoke()

        stopMetricsPolling()

        _currentStage.value = 0
        _currentStageName.value = ""
        _showSessionControls.value = false
        _runtimeBitrateSupported.value = false
        _hostStatistics.value = null

        // Stop native callbacks and interrupt blocking LiStartConnection
        try { MoonBridge.interruptConnection() } catch (_: Throwable) {}

        val connectionJob = streamJob
        streamJob = null

        stoppingJob = scope.launch(Dispatchers.IO) {
            try {
                connectionJob?.cancelAndJoin()
                MoonBridge.stopConnection()
            } catch (t: Throwable) {
                AppLogger.warn("Error stopping native connection: ${t.message}", TAG)
            }

            withContext(Dispatchers.Main) {
                videoDecoder?.stop()
                videoDecoder = null
                audioEngine?.stop()
                audioEngine = null

                MoonBridge.connectionListener = null
                MoonBridge.videoDecoderListener = null
                MoonBridge.audioRendererListener = null
            }

            if (hostToQuit != null && shouldQuit) {
                try {
                    httpClient.quitApp(hostToQuit)
                } catch (e: Exception) {
                    AppLogger.error("Failed to quit app on host: ${e.message}", TAG)
                }
            }
        }
    }

    fun disconnectStream() {
        stopSession(forceQuitApp = false)
    }

    private val _isQuittingApp = MutableStateFlow(false)
    val isQuittingApp: StateFlow<Boolean> = _isQuittingApp.asStateFlow()

    fun quitRunningApp() {
        if (_isQuittingApp.value) return
        val host = activeHost ?: return
        _isQuittingApp.value = true
        _hostControlMessage.value = null
        scope.launch {
            try {
                if (httpClient.quitApp(host)) {
                    _sessionError.value = null
                    stopSession(forceQuitApp = false)
                } else {
                    _hostControlMessage.value = "Sunshine did not confirm quitting the game. Check the host and try again."
                }
            } catch (e: Exception) {
                _hostControlMessage.value = "Could not quit the game: ${e.message}"
            } finally {
                _isQuittingApp.value = false
            }
        }
    }

    fun sendUtf8Text(text: String) {
        try {
            if (!_isStreaming.value || text.isEmpty()) return
            val result = MoonBridge.sendUtf8Text(text)
            if (result != 0) AppLogger.warn("Remote text input could not be queued ($result)", TAG)
        } catch (t: Throwable) {
            AppLogger.error("Failed to send UTF-8 text: ${t.message}", TAG)
        }
    }

    fun sendHostKeyPress(vk: Short, extended: Boolean = false) {
        val wireCode = (vk.toInt() or 0x8000).toShort()
        val flags: Byte = if (extended) KeycodeMappings.MODIFIER_EXTENDED else 0
        sendKeyboardEvent(wireCode, true, 0, flags)
        sendKeyboardEvent(wireCode, false, 0, flags)
    }

    fun sendKeyboardEvent(keyCode: Short, isDown: Boolean, modifiers: Byte = 0, flags: Byte = 0) {
        val keyAction: Byte = if (isDown) 0x03 else 0x04
        try {
            MoonBridge.sendKeyboardInput(keyCode, keyAction, modifiers, flags)
        } catch (_: Throwable) {}
    }

    fun handleKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!_isStreaming.value) return false
        val isDown = event.action == android.view.KeyEvent.ACTION_DOWN
        val hostKey = com.moonlight.material.stream.input.KeycodeMappings.hostKey(
            androidKeyCode = event.keyCode,
            swapWinAltKeys = currentSettings?.swapWinAltKeys ?: false
        ) ?: return false

        val modifiers = com.moonlight.material.stream.input.KeycodeMappings.extractModifiers(event, hostKey.extended)
        val keyAction = if (isDown) com.moonlight.material.stream.input.KeycodeMappings.KEY_ACTION_DOWN else com.moonlight.material.stream.input.KeycodeMappings.KEY_ACTION_UP
        return try {
            MoonBridge.sendKeyboardInput(hostKey.wireCode, keyAction, modifiers, hostKey.flags)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun applyRuntimeBitrate(mbps: Double) {
        if (mbps <= 0.0) return
        val host = activeHost ?: return
        val kbps = (mbps * 1000).toInt()
        val gen = sessionGeneration
        scope.launch(Dispatchers.IO) {
            _bitrateRequestInProgress.value = true
            try {
                if (_runtimeBitrateSupported.value) {
                    val applied = httpClient.setRuntimeBitrate(host, kbps)
                    if (sessionGeneration == gen) {
                        _runtimeBitrateKbps.value = applied
                        _hostControlMessage.value = "Bitrate updated to ${String.format("%.1f", applied / 1000.0)} Mbps."
                    }
                } else {
                    if (sessionGeneration == gen) {
                        _runtimeBitrateKbps.value = kbps
                        _hostControlMessage.value = "Bitrate set to $mbps Mbps."
                    }
                }
            } catch (e: Exception) {
                if (sessionGeneration == gen) {
                    _hostControlMessage.value = e.message ?: "Failed to set bitrate"
                }
            } finally {
                _bitrateRequestInProgress.value = false
            }
        }
    }

    fun runServerCommand(index: Int, name: String) {
        val host = activeHost ?: return
        if (!_isStreaming.value || _currentStageName.value != "Connected" || host.capabilities?.canRunCommands != true) return
        val gen = sessionGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val (updated, _) = httpClient.fetchServerInfo(host)
                if (sessionGeneration != gen || updated.capabilities?.canRunCommands != true) {
                    if (sessionGeneration == gen) _hostControlMessage.value = "Host commands changed. Reconnect before choosing a command again."
                    return@launch
                }
                val commands = updated.capabilities.commands
                if (index !in commands.indices || commands[index] != name) {
                    if (sessionGeneration == gen) _hostControlMessage.value = "Host commands changed. Reconnect before choosing a command again."
                    return@launch
                }
                val queued = MoonBridge.sendHostCommand(index.toByte())
                if (sessionGeneration == gen) {
                    _hostControlMessage.value = if (queued == 0) "Command submitted to host: $name" else "The command could not be queued."
                }
            } catch (e: Exception) {
                if (sessionGeneration == gen) _hostControlMessage.value = e.message ?: "Failed to send command"
            }
        }
    }

    fun disconnectRemoteMonitor() {
        val host = activeHost ?: return
        if (!isRemoteMonitorSession || _currentStageName.value != "Connected") return
        val gen = sessionGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val catalogue = httpClient.fetchAppList(host)
                val action = catalogue.firstOrNull { it.control == com.moonlight.material.core.models.AppControl.DISCONNECT_MONITOR } ?: return@launch
                stopSession()
                val result = httpClient.performHostAction(host, action)
                if (sessionGeneration == gen) {
                    _hostControlMessage.value = result
                }
            } catch (e: Exception) {
                if (sessionGeneration == gen) {
                    _hostControlMessage.value = e.message ?: "Failed to disconnect monitor"
                }
            }
        }
    }

    // --- MoonBridge.ConnectionListener ---

    override fun onStageStarting(stage: Int) {
        val stageName = try { MoonBridge.getStageName(stage) } catch (_: Throwable) { "Stage $stage" }
        scope.launch(Dispatchers.Main) {
            updateStage(stage, stageName)
        }
    }

    override fun onStageComplete(stage: Int) {
        // Stage completed successfully
    }

    override fun onStageFailed(stage: Int, errorCode: Int) {
        scope.launch(Dispatchers.Main) {
            handleStageFailed(stage, errorCode)
        }
    }

    override fun onConnectionStarted() {
        scope.launch(Dispatchers.Main) {
            markConnected()
        }
    }

    override fun onConnectionTerminated(errorCode: Int) {
        scope.launch(Dispatchers.Main) {
            if (errorCode != 0) {
                handleTerminated(errorCode)
            } else {
                stopSession()
            }
        }
    }

    override fun onConnectionStatusUpdate(connectionStatus: Int) {
        scope.launch(Dispatchers.Main) {
            updateConnectionStatus(connectionStatus)
        }
    }

    override fun onSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        videoDecoder?.setHdrMode(enabled, hdrMetadata)
    }

    override fun onRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        gamepadHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun onRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        gamepadHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun onSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        // Forwarded to GamepadHandler in M6
    }

    override fun onSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        // Forwarded to GamepadHandler in M6
    }

    // --- MoonBridge.VideoDecoderRendererListener & AudioRendererListener ---

    val videoBridgeListener = object : MoonBridge.VideoDecoderRendererListener {
        override fun onSetup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int {
            negotiatedWidth = width
            negotiatedHeight = height
            negotiatedFps = redrawRate
            val isAv1 = (videoFormat and 0xF000) != 0
            val isHevc = (videoFormat and 0x0F00) != 0
            var codecName = when {
                isAv1 -> "AV1"
                isHevc -> "HEVC"
                else -> "H.264"
            }
            if ((videoFormat and 0xCC04) != 0) {
                codecName += " 4:4:4"
            }
            negotiatedCodec = codecName
            return videoDecoder?.setup(videoFormat, width, height, redrawRate) ?: 0
        }

        override fun onStart() {
            videoDecoder?.start()
        }

        override fun onStop() {
            videoDecoder?.stop()
        }

        override fun onCleanup() {
            videoDecoder?.cleanup()
        }

        override fun onSubmitDecodeUnit(
            frameData: ByteArray,
            length: Int,
            bufferType: Int,
            frameNumber: Int,
            frameType: Int,
            frameHostProcessingLatency: Char,
            receiveTimeUs: Long,
            enqueueTimeUs: Long
        ): Int {
            return videoDecoder?.submitDecodeUnit(
                frameData, length, bufferType, frameNumber, frameType,
                frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs
            ) ?: 0
        }
    }

    val audioBridgeListener = object : MoonBridge.AudioRendererListener {
        override fun onInit(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
            return audioEngine?.init(audioConfiguration, sampleRate, samplesPerFrame) ?: 0
        }

        override fun onStart() {
            audioEngine?.start()
        }

        override fun onStop() {
            audioEngine?.stop()
        }

        override fun onCleanup() {
            audioEngine?.cleanup()
        }

        override fun onPlaySample(sampleData: ShortArray) {
            audioEngine?.playSample(sampleData)
        }
    }

    // --- VideoDecoderListener ---

    override fun onFrameDecoded(decodeTimeMs: Double) {
        // Metrics are sampled atomically from the decoder's monotonic tracker.
    }

    override fun onFrameDropped() { }

    override fun onFormatChanged(width: Int, height: Int) {
        negotiatedWidth = width
        negotiatedHeight = height
    }

    override fun onDecoderError(error: String) {
        _sessionError.value = error
        stopSession()
    }

    // --- GamepadListener ---

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
        if (!_isStreaming.value) return
        try {
            MoonBridge.sendMultiControllerInput(
                controllerNumber,
                1.toShort(),
                buttonFlags,
                leftTrigger,
                rightTrigger,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
        } catch (_: Throwable) {}
    }

    override fun onEmulatedMouseMove(deltaX: Short, deltaY: Short) {
        onMouseMoveRelative(deltaX, deltaY)
    }

    override fun onEmulatedMouseButton(button: Byte, isDown: Boolean) {
        onMouseButton(button, isDown)
    }

    override fun onEmulatedMouseScroll(scroll: Short) {
        onMouseScroll(scroll)
    }

    // --- MouseEventListener ---

    override fun onNativeTouch(eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float, major: Float, minor: Float): Int {
        if (!_isStreaming.value || _currentStageName.value != "Connected") return 0
        return try {
            val result = MoonBridge.sendTouchEvent(eventType, pointerId, x, y, pressure, major, minor, (-1).toShort())
            if (eventType.toInt() == 1) {
                AppLogger.debug("Native touch down: result=$result, hostFeatures=${MoonBridge.getHostFeatureFlags()}", "INPUT")
            }
            result
        } catch (t: Throwable) {
            AppLogger.warn("Native touch unavailable: ${t.message}", TAG)
            -5501
        }
    }

    override fun onMouseMoveRelative(deltaX: Short, deltaY: Short) {
        if (!_isStreaming.value) return
        try {
            MoonBridge.sendMouseMove(deltaX, deltaY)
        } catch (_: Throwable) {}
    }

    override fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short) {
        if (!_isStreaming.value) return
        try {
            MoonBridge.sendMousePosition(x, y, screenW, screenH)
        } catch (_: Throwable) {}
    }

    override fun onMouseButton(button: Byte, isDown: Boolean) {
        if (!_isStreaming.value) return
        try {
            val eventType: Byte = if (isDown) 0x01 else 0x02
            MoonBridge.sendMouseButton(eventType, button)
        } catch (_: Throwable) {}
    }

    override fun onMouseScroll(amount: Short) {
        if (!_isStreaming.value) return
        try {
            MoonBridge.sendMouseHighResScroll(amount)
        } catch (_: Throwable) {}
    }
}
