package com.moonlight.material.core.state

import android.view.Surface
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.HostHardwareMetrics
import com.moonlight.material.core.models.PerformanceMetrics
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.security.CryptoUtils
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.networking.SunshineHTTPClient
import com.moonlight.material.stream.audio.AudioPlaybackEngine
import com.moonlight.material.stream.control.ControlProtocol
import com.moonlight.material.stream.input.GamepadHandler
import com.moonlight.material.stream.input.GamepadListener
import com.moonlight.material.stream.input.MouseEventListener
import com.moonlight.material.stream.input.TouchMouseHandler
import com.moonlight.material.stream.rtsp.RtspHandshakeResult
import com.moonlight.material.stream.rtsp.RtspSession
import com.moonlight.material.stream.video.MediaCodecDecoder
import com.moonlight.material.stream.video.VideoDecoderListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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

    var activeHost: Host? = null
        private set
    var activeApp: StreamApp? = null
        private set
    var activeSettings: StreamSettings? = null
        private set

    private var videoDecoder: MediaCodecDecoder? = null
    private var audioEngine: AudioPlaybackEngine? = null
    private var rtspSession: RtspSession? = null

    val gamepadHandler = GamepadHandler(this)
    val touchMouseHandler = TouchMouseHandler(this)

    private var inputSocket: DatagramSocket? = null
    private var controlAddress: InetAddress? = null
    private var controlPort: Int = 47999

    private var metricsJob: Job? = null
    private var keepaliveJob: Job? = null

    fun togglePerformanceOverlay() {
        _showPerformanceOverlay.value = !_showPerformanceOverlay.value
    }

    fun toggleHostStatistics() {
        _showHostStatistics.value = !_showHostStatistics.value
    }

    fun startSession(
        host: Host,
        app: StreamApp,
        settings: StreamSettings,
        surface: Surface
    ) {
        if (_isStreaming.value) return

        activeHost = host
        activeApp = app
        activeSettings = settings
        _isStreaming.value = true
        _sessionError.value = null
        _currentStage.value = 1
        _currentStageName.value = "Requesting session from host…"

        val rikey = CryptoUtils.randomBytes(16)
        val rikeyId = (1L..0xFFFFFFFFL).random()
        val rikeyHex = CryptoUtils.hexString(rikey)

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Launch or resume app via Sunshine HTTP
                val sessionUrl: String? = if (app.matchesRunning(host) || app.control?.id == 1) {
                    httpClient.resumeApp(host, settings, rikeyHex, rikeyId)
                } else {
                    httpClient.launchApp(host, app.id, app.uuid, settings, rikeyHex, rikeyId)
                }

                _currentStage.value = 2
                _currentStageName.value = "Negotiating RTSP stream…"

                // 2. Perform RTSP Handshake
                val rtsp = RtspSession(host.effectiveAddress, 48010)
                rtspSession = rtsp
                val handshakeResult = rtsp.performHandshake()

                controlAddress = InetAddress.getByName(host.effectiveAddress)
                controlPort = handshakeResult.controlPort
                inputSocket = DatagramSocket()

                _currentStage.value = 3
                _currentStageName.value = "Starting low-latency video and audio…"

                // 3. Start Video Decoder
                videoDecoder = MediaCodecDecoder(
                    surface = surface,
                    width = settings.width,
                    height = settings.height,
                    fps = settings.fps,
                    codec = settings.codec,
                    enableHdr = settings.enableHdr,
                    listener = this@StreamSessionController
                ).apply { start() }

                // 4. Start Audio Engine
                audioEngine = AudioPlaybackEngine().apply {
                    start(settings.audioMode, settings.muteAudioOnFocusLoss)
                }

                // 5. Configure input
                gamepadHandler.swapFaceButtons = settings.features.swapFaceButtons
                gamepadHandler.gamepadMouseEnabled = settings.features.gamepadMouse
                touchMouseHandler.isDesktopMouseMode = settings.features.desktopMouse
                touchMouseHandler.mouseSensitivityX = settings.mouseSensitivityX
                touchMouseHandler.mouseSensitivityY = settings.mouseSensitivityY
                touchMouseHandler.swapMouseButtons = settings.features.swapMouseButtons
                touchMouseHandler.reverseScroll = settings.features.reverseScroll

                _runtimeBitrateKbps.value = settings.streamBitrateKbps(isRemote = false)
                _currentStage.value = 4
                _currentStageName.value = "Connected"
                AppLogger.info("Stream connected successfully!", TAG)

                startKeepAliveAndMetrics()

            } catch (e: Exception) {
                AppLogger.error("Failed to start session: ${e.message}", TAG)
                _sessionError.value = e.message ?: "Connection failed"
                stopSession()
            }
        }
    }

    private fun startKeepAliveAndMetrics() {
        // Keepalive / Ping Loop
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isStreaming.value) {
                sendPing()
                delay(1000)
            }
        }

        // Metrics polling loop for Performance HUD
        metricsJob = scope.launch(Dispatchers.Main) {
            while (isActive && _isStreaming.value) {
                val curFps = videoDecoder?.currentFps ?: 0.0
                val targetFps = activeSettings?.fps ?: 60
                val resolutionStr = "${activeSettings?.width ?: 1920}x${activeSettings?.height ?: 1080}"
                val codecStr = activeSettings?.codec?.displayName ?: "H.264"
                val bitrateMb = _runtimeBitrateKbps.value / 1000.0

                _metrics.value = _metrics.value.copy(
                    currentFps = curFps,
                    targetFps = targetFps,
                    resolution = resolutionStr,
                    currentCodec = codecStr,
                    bitrateMbps = bitrateMb,
                    vsyncEnabled = activeSettings?.enableVsync ?: true,
                    pacingEnabled = activeSettings?.enableFramePacing ?: true
                )
                delay(500)
            }
        }
    }

    private fun sendPing() {
        val pingPacket = ByteArray(4) { 0 }
        sendControlPacket(pingPacket)
    }

    private fun sendControlPacket(data: ByteArray) {
        val socket = inputSocket ?: return
        val addr = controlAddress ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val packet = DatagramPacket(data, data.size, addr, controlPort)
                socket.send(packet)
            } catch (e: Exception) {
                // Ignore transient send errors
            }
        }
    }

    fun applyRuntimeBitrate(mbps: Double) {
        val kbps = (mbps * 1000).toInt()
        _runtimeBitrateKbps.value = kbps
        AppLogger.info("Applied runtime bitrate: $mbps Mbps", TAG)
    }

    fun sendKeyboardEvent(keyCode: Short, isDown: Boolean) {
        val packet = ControlProtocol.createKeyboardPacket(keyCode, isDown)
        sendControlPacket(packet)
    }

    fun sendClipboardText(text: String) {
        val packet = ControlProtocol.createUtf8TextPacket(text)
        sendControlPacket(packet)
        AppLogger.info("Sent clipboard text: ${text.take(30)}…", TAG)
    }

    // VideoDecoderListener
    override fun onFrameDecoded(decodeTimeMs: Double) {
        _metrics.value = _metrics.value.copy(decodeTimeMs = decodeTimeMs)
    }

    override fun onFrameDropped() {
        _metrics.value = _metrics.value.copy(
            networkDroppedFrames = _metrics.value.networkDroppedFrames + 1
        )
    }

    override fun onDecoderError(error: String) {
        _sessionError.value = "Video decoder error: $error"
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
        val packet = ControlProtocol.createControllerPacket(
            controllerNumber,
            buttonFlags,
            leftTrigger,
            rightTrigger,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
        sendControlPacket(packet)
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

    // MouseEventListener
    override fun onMouseMoveRelative(deltaX: Short, deltaY: Short) {
        val packet = ControlProtocol.createMouseMoveRelativePacket(deltaX, deltaY)
        sendControlPacket(packet)
    }

    override fun onMouseMoveAbsolute(x: Short, y: Short, screenW: Short, screenH: Short) {
        val packet = ControlProtocol.createMouseMoveAbsolutePacket(x, y, screenW, screenH)
        sendControlPacket(packet)
    }

    override fun onMouseButton(button: Byte, isDown: Boolean) {
        val packet = ControlProtocol.createMouseButtonPacket(button, isDown)
        sendControlPacket(packet)
    }

    override fun onMouseScroll(amount: Short) {
        val packet = ControlProtocol.createMouseScrollPacket(amount)
        sendControlPacket(packet)
    }

    fun stopSession() {
        _isStreaming.value = false
        _currentStageName.value = "Disconnected"
        metricsJob?.cancel()
        metricsJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null

        videoDecoder?.stop()
        videoDecoder = null

        audioEngine?.stop()
        audioEngine = null

        rtspSession?.teardown()
        rtspSession = null

        try { inputSocket?.close() } catch (e: Exception) {}
        inputSocket = null

        val host = activeHost
        val app = activeApp
        val quitOnStop = activeSettings?.quitAppOnStop == true
        if (host != null && app != null && quitOnStop) {
            scope.launch(Dispatchers.IO) {
                httpClient.quitApp(host)
            }
        }
    }
}
