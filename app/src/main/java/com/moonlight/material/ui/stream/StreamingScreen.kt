package com.moonlight.material.ui.stream

import android.hardware.input.InputManager
import android.view.InputDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
    val sessionError by session.sessionError.collectAsState()
    val metrics by session.metrics.collectAsState()
    val showHUD by session.showPerformanceOverlay.collectAsState()
    val showControlsSheet by session.showSessionControls.collectAsState()

    var touchAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var showKeyboardOverlay by remember { mutableStateOf(false) }
    var controllerEnabled by rememberSaveable { mutableStateOf(settings.features.touchOverlayEnabled) }
    val context = LocalContext.current
    var physicalControllerConnected by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        fun refreshDevices() {
            physicalControllerConnected = manager.inputDeviceIds.any { id ->
                manager.getInputDevice(id)?.let { device ->
                    !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                        device.supportsSource(InputDevice.SOURCE_JOYSTICK))
                } == true
            }
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
            override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
            override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
        }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        refreshDevices()
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    SideEffect { session.localInputActive = showKeyboardOverlay || showControlsSheet || stageName != "Connected" }
    DisposableEffect(session) {
        onDispose { session.localInputActive = false }
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
        val videoAspect = settings.width.toFloat() / settings.height.coerceAtLeast(1)
        val videoSize = if (maxWidth / maxHeight > videoAspect)
            Modifier.fillMaxHeight().aspectRatio(videoAspect)
        else Modifier.fillMaxWidth().aspectRatio(videoAspect)
        // Video SurfaceView
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            session.startSession(host, app, settings, holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.stopSession()
                        }
                    })

                    setOnGenericMotionListener { _, event ->
                        if (session.gamepadHandler.handleGenericMotionEvent(event)) {
                            true
                        } else {
                            session.touchMouseHandler.handleGenericMotion(event)
                        }
                    }

                    setOnKeyListener { _, _, event ->
                        if (session.localInputActive) return@setOnKeyListener false
                        if (session.gamepadHandler.handleKeyEvent(event)) {
                            true
                        } else {
                            session.handleKeyEvent(event)
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.Center).then(videoSize)
        )

        // Compose owns the gesture hit target. SurfaceView touch interop may not
        // receive events when Compose overlays are present, even with no gamepad.
        Box(Modifier.align(Alignment.Center).then(videoSize)
            .onSizeChanged { touchAreaSize = it }
            .pointerInteropFilter { event ->
                if (session.showSessionControls.value) return@pointerInteropFilter true
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    com.moonlight.material.core.utils.AppLogger.debug(
                        "Stream touch down, source=${event.source}, touchpad=${session.touchMouseHandler.isDesktopMouseMode}", "INPUT")
                }
                session.touchMouseHandler.handleTouchEvent(event, touchAreaSize.width, touchAreaSize.height)
            })

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

        // Always reachable, including while connecting. Keep clear of virtual buttons.
        if (!showControlsSheet) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { showKeyboardOverlay = !showKeyboardOverlay }, enabled = stageName == "Connected", modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp).background(MidnightBlue.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Keyboard, if (showKeyboardOverlay) "Hide Keyboard" else "Show Keyboard",
                            tint = if (showKeyboardOverlay) CyanAccent else TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = { showKeyboardOverlay = false; session.openSessionControls() }, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp).background(MidnightBlue.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Tune, "Session Controls", tint = CyanAccent, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = {
                    controllerEnabled = !controllerEnabled
                    onControllerVisibilityChanged(controllerEnabled)
                }, enabled = !physicalControllerConnected, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp).background(MidnightBlue.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SportsEsports,
                            if (physicalControllerConnected) "Controller hidden: physical controller connected"
                            else if (controllerEnabled) "Hide On-screen Controller" else "Show On-screen Controller",
                            tint = if (controllerEnabled && !physicalControllerConnected) CyanAccent else TextPrimary,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
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
                onTouchModeChanged = onTouchModeChanged,
                onFinish = onFinish,
                onDismiss = { session.closeSessionControls() }
            )
        }

        // Error Dialog
        if (sessionError != null) {
            AlertDialog(
                onDismissRequest = onFinish,
                title = { Text("Stream Terminated", color = RoseError, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
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
