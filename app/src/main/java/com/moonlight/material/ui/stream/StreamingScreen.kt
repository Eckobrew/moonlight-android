package com.moonlight.material.ui.stream

import android.hardware.input.InputManager
import android.view.InputDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SportsEsports
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.stream.input.GamepadHandler
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StreamingScreen(
    host: Host,
    app: StreamApp,
    settings: StreamSettings,
    session: StreamSessionController,
    onControllerVisibilityChanged: (Boolean) -> Unit = {},
    onTouchModeChanged: (Boolean) -> Unit = {},
    onFinish: () -> Unit
) {
    val isStreaming by session.isStreaming.collectAsState()
    val stageName by session.currentStageName.collectAsState()
    val connectionWarning by session.connectionWarning.collectAsState()
    val sessionError by session.sessionError.collectAsState()
    val metrics by session.metrics.collectAsState()
    val showHUD by session.showPerformanceOverlay.collectAsState()
    val showControlsSheet by session.showSessionControls.collectAsState()
    val negotiatedSize by session.negotiatedVideoSize.collectAsState()

    var touchAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var showKeyboardOverlay by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showKeyboardOverlay, imeVisible) {
        if (!showKeyboardOverlay) keyboardWasVisible = false
        else if (imeVisible) keyboardWasVisible = true
        else if (keyboardWasVisible) {
            showKeyboardOverlay = false
            keyboardWasVisible = false
        }
    }
    var controllerEnabled by rememberSaveable { mutableStateOf(settings.features.touchOverlayEnabled) }
    val context = LocalContext.current
    var physicalMouseConnected by remember { mutableStateOf(false) }
    var physicalControllerConnected by remember { mutableStateOf(false) }
    DisposableEffect(context, session) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        fun refreshDevices() {
            val inputDevices = manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull()
            physicalMouseConnected = inputDevices.any { dev ->
                !dev.isVirtual &&
                    (!dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) || Build.HARDWARE == "cheets") &&
                    (dev.supportsSource(InputDevice.SOURCE_MOUSE) ||
                        dev.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                        (dev.supportsSource(InputDevice.SOURCE_TOUCHPAD) && !GamepadHandler.isGamepad(dev)))
            }
            val devices = inputDevices.filter(GamepadHandler::isGamepad)
            physicalControllerConnected = devices.isNotEmpty()
            session.updatePhysicalControllers(devices)
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
            override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
            override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
        }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        refreshDevices()
        onDispose {
            manager.unregisterInputDeviceListener(listener)
            session.updatePhysicalControllers(emptyList())
        }
    }
    // Read overlay state during composition so this effect updates when either overlay changes.
    val keyboardOverlayActive = showKeyboardOverlay
    SideEffect { session.keyboardOverlayActive = keyboardOverlayActive }
    DisposableEffect(session) {
        onDispose { session.keyboardOverlayActive = false }
    }
    var hadStream by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming, sessionError) {
        if (isStreaming) hadStream = true
        else if (hadStream && sessionError == null) onFinish()
    }

    // Intercept back gesture & back button during active streaming
    BackHandler(enabled = isStreaming) {
        when {
            showKeyboardOverlay -> showKeyboardOverlay = false
            showControlsSheet -> session.closeSessionControls()
            else -> session.openSessionControls()
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        val streamW = negotiatedSize?.first ?: settings.width
        val streamH = negotiatedSize?.second ?: settings.height
        val videoAspect = streamW.toFloat() / streamH.coerceAtLeast(1)
        val videoSize = if (settings.features.stretchToFit) {
            Modifier.fillMaxSize()
        } else if (maxWidth / maxHeight > videoAspect) {
            Modifier.fillMaxHeight().aspectRatio(videoAspect)
        } else {
            Modifier.fillMaxWidth().aspectRatio(videoAspect)
        }
        // Video SurfaceView
        val inputCaptured by session.inputCaptured.collectAsState()
        val localCursorVisible by session.localCursorVisible.collectAsState()
        val captureMouse = inputCaptured && physicalMouseConnected && stageName == "Connected" && !showKeyboardOverlay && !showControlsSheet
        AndroidView(
            factory = { context ->
                object : SurfaceView(context) {
                    private val retryPointerCaptureRunnable = Runnable {
                        if (hasWindowFocus() && hasFocus() && !session.localInputActive &&
                            physicalMouseConnected && session.inputCaptured.value && !hasPointerCapture()
                        ) {
                            requestPointerCapture()
                        }
                    }

                    override fun onPointerCaptureChange(hasCapture: Boolean) {
                        super.onPointerCaptureChange(hasCapture)
                        if (!hasCapture) session.touchMouseHandler.releasePhysicalMouse()
                    }

                    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                        super.onWindowFocusChanged(hasWindowFocus)
                        removeCallbacks(retryPointerCaptureRunnable)
                        if (hasWindowFocus && hasFocus() && !session.localInputActive &&
                            physicalMouseConnected && session.inputCaptured.value
                        ) {
                            requestPointerCapture()
                            // Android 10+ (Q+) may drop pointer capture requests during window focus transitions; retry after 500ms
                            postDelayed(retryPointerCaptureRunnable, 500L)
                        }
                    }
                }.apply {
                    isFocusableInTouchMode = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requestUnbufferedDispatch(
                            InputDevice.SOURCE_CLASS_BUTTON or
                                InputDevice.SOURCE_CLASS_JOYSTICK or
                                InputDevice.SOURCE_CLASS_POINTER or
                                InputDevice.SOURCE_CLASS_POSITION or
                                InputDevice.SOURCE_CLASS_TRACKBALL
                        )
                    }
                    setOnCapturedPointerListener { view, event ->
                        session.inputCaptured.value && !session.localInputActive && session.touchMouseHandler.handleGenericMotion(event, view.width, view.height)
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Disable Surface producer throttling on Android 16+ (API 36+) to avoid compositor frame holdback
                            if (Build.VERSION.SDK_INT >= 36) {
                                try {
                                    val method = android.view.Surface::class.java.getMethod(
                                        "setProducerThrottlingEnabled",
                                        Boolean::class.javaPrimitiveType
                                    )
                                    method.invoke(holder.surface, false)
                                } catch (_: Throwable) {}
                            }
                            val pacingMode = settings.effectiveFramePacingMode
                            val targetRate = if (pacingMode == com.moonlight.material.core.models.FramePacingMode.MIN_LATENCY ||
                                pacingMode == com.moonlight.material.core.models.FramePacingMode.MAX_SMOOTHNESS
                            ) {
                                session.displayRefreshRate().coerceAtLeast(settings.fps.toFloat())
                            } else {
                                settings.fps.toFloat()
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                holder.surface.setFrameRate(
                                    targetRate,
                                    android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                    android.view.Surface.CHANGE_FRAME_RATE_ALWAYS
                                )
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                holder.surface.setFrameRate(
                                    targetRate,
                                    android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                                )
                            }
                            if (session.isStreaming.value) {
                                session.updateSurface(holder.surface)
                            } else {
                                session.startSession(host, app, settings, holder.surface)
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.stopSession()
                        }
                    })

                    setOnGenericMotionListener { view, event ->
                        session.inputCaptured.value && !session.localInputActive && session.touchMouseHandler.handleGenericMotion(event, view.width, view.height)
                    }

                    setOnKeyListener { _, _, event ->
                        if (session.localInputActive) return@setOnKeyListener false
                        session.handleKeyEvent(event)
                    }
                }
            },
            update = { view ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.pointerIcon = if (inputCaptured && !localCursorVisible && stageName == "Connected" && !showControlsSheet) {
                        android.view.PointerIcon.getSystemIcon(view.context, android.view.PointerIcon.TYPE_NULL)
                    } else {
                        null
                    }
                }
                if (captureMouse) {
                    view.requestFocus()
                    if (view.hasWindowFocus() && !view.hasPointerCapture()) {
                        view.requestPointerCapture()
                        view.postDelayed({
                            if (view.hasWindowFocus() && view.hasFocus() && !session.localInputActive &&
                                physicalMouseConnected && session.inputCaptured.value && !view.hasPointerCapture()
                            ) {
                                view.requestPointerCapture()
                            }
                        }, 500L)
                    }
                } else {
                    if (view.hasPointerCapture()) view.releasePointerCapture()
                    session.touchMouseHandler.releasePhysicalMouse()
                }
            },
            modifier = Modifier.align(Alignment.Center)
                .then(videoSize)
                .onSizeChanged { touchAreaSize = it }
        )

        // Compose owns the full-screen gesture hit target (so relative touchpad mode works on black bars too,
        // while absolute/native touch maps coordinates relative to the centered video viewport).
        var fullScreenSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { fullScreenSize = it }
                .pointerInteropFilter { event ->
                    if (session.showSessionControls.value) return@pointerInteropFilter true
                    val vW = touchAreaSize.width.takeIf { it > 0 } ?: fullScreenSize.width
                    val vH = touchAreaSize.height.takeIf { it > 0 } ?: fullScreenSize.height
                    val offsetX = -((fullScreenSize.width - vW) / 2f).coerceAtLeast(0f)
                    val offsetY = -((fullScreenSize.height - vH) / 2f).coerceAtLeast(0f)
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        com.moonlight.material.core.utils.AppLogger.debug(
                            "Stream touch down, source=${event.source}, touchpad=${session.touchMouseHandler.isDesktopMouseMode}", "INPUT"
                        )
                    }
                    if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                        event.offsetLocation(offsetX, offsetY)
                        val handled = session.inputCaptured.value && !session.localInputActive &&
                            session.touchMouseHandler.handleGenericMotion(event, vW, vH)
                        event.offsetLocation(-offsetX, -offsetY)
                        handled
                    } else {
                        session.touchMouseHandler.handleTouchEvent(event, vW, vH, offsetX, offsetY)
                    }
                }
        )

        // Connecting indicator
        if (stageName != "Connected" && sessionError == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MidnightBlue.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = CyanAccent)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stageName, color = TextPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            }
        }

        // Virtual Touch Overlay
        if (controllerEnabled && !physicalControllerConnected && !showKeyboardOverlay && !showControlsSheet && stageName == "Connected") {
            VirtualTouchOverlay(
                opacity = settings.features.touchOverlayOpacity,
                swapFaceButtons = settings.features.swapFaceButtons,
                hapticsEnabled = settings.features.touchHapticsEnabled,
                showQuickToggleButton = settings.features.showToggleControllerButton,
                listener = session
            )
        }

        // Performance HUD overlay (text-only in top right)
        if (showHUD && stageName == "Connected") {
            val hostStats by session.hostStatistics.collectAsState()
            PerformanceHUD(
                metrics = metrics,
                hostStatistics = hostStats,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }

        // On-Screen Keyboard Bar
        if (showKeyboardOverlay && stageName == "Connected") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                StreamKeyboardBar(session = session)
            }
        }

        // Session Controls Bottom Sheet
        if (showControlsSheet) {
            SessionControlsSheet(
                session = session,
                app = app,
                onShowKeyboard = { showKeyboardOverlay = true },
                controllerVisible = controllerEnabled && !physicalControllerConnected,
                controllerConnected = physicalControllerConnected,
                onToggleController = {
                    controllerEnabled = !controllerEnabled
                    onControllerVisibilityChanged(controllerEnabled)
                },
                onTouchModeChanged = onTouchModeChanged,
                onFinish = onFinish,
                onDismiss = { session.closeSessionControls() }
            )
        }

        val warning = if (physicalControllerConnected && session.controllerPermissionDenied) {
            "The host blocks controller input for this device. Enable controller permission in the host's paired clients settings."
        } else {
            connectionWarning
        }
        if (warning != null && stageName == "Connected" && !showControlsSheet && !showKeyboardOverlay) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                color = MidnightBlue.copy(alpha = 0.85f), shape = MaterialTheme.shapes.small) {
                Text(warning, color = AmberWarning, modifier = Modifier.padding(12.dp))
            }
        }

        // Error Dialog
        if (sessionError != null) {
            AlertDialog(
                onDismissRequest = onFinish,
                title = { Text("Streaming Error", color = RoseError, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = { Text(sessionError ?: "", color = TextPrimary) },
                confirmButton = {
                    Button(
                        onClick = onFinish,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Text("OK", color = DeepNavy)
                    }
                },
                containerColor = MidnightBlue
            )
        }
    }
}
