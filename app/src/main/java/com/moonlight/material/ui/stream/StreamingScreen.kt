package com.moonlight.material.ui.stream

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.ui.theme.*

@Composable
fun StreamingScreen(
    host: Host,
    app: StreamApp,
    settings: StreamSettings,
    session: StreamSessionController,
    onFinish: () -> Unit
) {
    val isStreaming by session.isStreaming.collectAsState()
    val stageName by session.currentStageName.collectAsState()
    val sessionError by session.sessionError.collectAsState()
    val metrics by session.metrics.collectAsState()
    val showHUD by session.showPerformanceOverlay.collectAsState()

    var showControlsSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
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

                    setOnTouchListener { v, event ->
                        session.touchMouseHandler.handleTouchEvent(event, v.width, v.height)
                    }

                    setOnGenericMotionListener { _, event ->
                        if (session.gamepadHandler.handleGenericMotionEvent(event)) {
                            true
                        } else {
                            session.touchMouseHandler.handleGenericMotion(event)
                        }
                    }

                    setOnKeyListener { _, _, event ->
                        if (session.gamepadHandler.handleKeyEvent(event)) {
                            true
                        } else {
                            session.handleKeyEvent(event)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
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
        if (settings.features.touchOverlayEnabled && stageName == "Connected") {
            VirtualTouchOverlay(
                opacity = settings.features.touchOverlayOpacity,
                swapFaceButtons = settings.features.swapFaceButtons,
                listener = session
            )
        }

        // Performance HUD overlay
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

        // Floating Session Controls button
        if (stageName == "Connected") {
            IconButton(
                onClick = { showControlsSheet = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(40.dp)
                    .background(MidnightBlue.copy(alpha = 0.7f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Session Controls",
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Controls Bottom Sheet
        if (showControlsSheet) {
            SessionControlsSheet(
                session = session,
                onDismiss = { showControlsSheet = false }
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
