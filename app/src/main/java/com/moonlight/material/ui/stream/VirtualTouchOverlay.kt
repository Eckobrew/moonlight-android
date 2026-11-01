package com.moonlight.material.ui.stream

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.stream.input.ControllerButtonFlags
import com.moonlight.material.stream.input.GamepadListener
import com.moonlight.material.ui.theme.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun VirtualTouchOverlay(
    opacity: Float = 0.65f,
    swapFaceButtons: Boolean = false,
    listener: GamepadListener,
    modifier: Modifier = Modifier
) {
    var buttonFlags by remember { mutableStateOf(0) }
    var leftTrigger by remember { mutableStateOf<Byte>(0) }
    var rightTrigger by remember { mutableStateOf<Byte>(0) }
    var leftStickX by remember { mutableStateOf<Short>(0) }
    var leftStickY by remember { mutableStateOf<Short>(0) }
    var rightStickX by remember { mutableStateOf<Short>(0) }
    var rightStickY by remember { mutableStateOf<Short>(0) }

    fun updateController() {
        listener.onControllerStateChanged(
            controllerNumber = 0,
            buttonFlags = buttonFlags,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(opacity)
    ) {
        // Top Shoulder Buttons & Triggers (LB, LT, RB, RT, Select, Start)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Bumpers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchActionButton("LT", 60.dp, 36.dp) { isDown ->
                    leftTrigger = if (isDown) 255.toByte() else 0.toByte()
                    updateController()
                }
                TouchActionButton("LB", 60.dp, 36.dp) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or ControllerButtonFlags.LB else buttonFlags and ControllerButtonFlags.LB.inv()
                    updateController()
                }
            }

            // Center Start / Select
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TouchActionButton("SELECT", 64.dp, 32.dp) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or ControllerButtonFlags.BACK else buttonFlags and ControllerButtonFlags.BACK.inv()
                    updateController()
                }
                TouchActionButton("START", 64.dp, 32.dp) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or ControllerButtonFlags.START else buttonFlags and ControllerButtonFlags.START.inv()
                    updateController()
                }
            }

            // Right Bumpers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchActionButton("RB", 60.dp, 36.dp) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or ControllerButtonFlags.RB else buttonFlags and ControllerButtonFlags.RB.inv()
                    updateController()
                }
                TouchActionButton("RT", 60.dp, 36.dp) { isDown ->
                    rightTrigger = if (isDown) 255.toByte() else 0.toByte()
                    updateController()
                }
            }
        }

        // Bottom Left: Analog Joystick + D-Pad
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 24.dp)
        ) {
            VirtualJoystick(
                size = 140.dp,
                onStickMove = { x, y ->
                    leftStickX = (x * 32767).toInt().toShort()
                    leftStickY = (-y * 32767).toInt().toShort()
                    updateController()
                }
            )
        }

        // Bottom Right: Action Buttons (A, B, X, Y)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
                .size(150.dp)
        ) {
            val aButton = if (swapFaceButtons) ControllerButtonFlags.B else ControllerButtonFlags.A
            val bButton = if (swapFaceButtons) ControllerButtonFlags.A else ControllerButtonFlags.B
            val xButton = if (swapFaceButtons) ControllerButtonFlags.Y else ControllerButtonFlags.X
            val yButton = if (swapFaceButtons) ControllerButtonFlags.X else ControllerButtonFlags.Y

            // Y (Top)
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                TouchCircleButton("Y", CyanAccent) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or yButton else buttonFlags and yButton.inv()
                    updateController()
                }
            }
            // A (Bottom)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                TouchCircleButton("A", EmeraldGreen) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or aButton else buttonFlags and aButton.inv()
                    updateController()
                }
            }
            // X (Left)
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                TouchCircleButton("X", PurpleAccent) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or xButton else buttonFlags and xButton.inv()
                    updateController()
                }
            }
            // B (Right)
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                TouchCircleButton("B", RoseError) { isDown ->
                    buttonFlags = if (isDown) buttonFlags or bButton else buttonFlags and bButton.inv()
                    updateController()
                }
            }
        }
    }
}

@Composable
private fun VirtualJoystick(
    size: androidx.compose.ui.unit.Dp,
    onStickMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 60f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MidnightBlue.copy(alpha = 0.5f))
            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {},
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onStickMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onStickMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newOffset = knobOffset + dragAmount
                        val distance = hypot(newOffset.x, newOffset.y)
                        knobOffset = if (distance > maxRadius) {
                            Offset(
                                (newOffset.x / distance) * maxRadius,
                                (newOffset.y / distance) * maxRadius
                            )
                        } else {
                            newOffset
                        }
                        onStickMove(knobOffset.x / maxRadius, knobOffset.y / maxRadius)
                    }
                )
            }
    ) {
        // Knob
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.toInt(), knobOffset.y.toInt()) }
                .size(54.dp)
                .clip(CircleShape)
                .background(CyanAccent.copy(alpha = 0.8f))
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun TouchActionButton(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) CyanAccent else MidnightBlue.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressChanged(true)
                        tryAwaitRelease()
                        isPressed = false
                        onPressChanged(false)
                    }
                )
            }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) DeepNavy else TextPrimary
        )
    }
}

@Composable
private fun TouchCircleButton(
    label: String,
    accentColor: Color,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (isPressed) accentColor else MidnightBlue.copy(alpha = 0.6f))
            .border(2.dp, if (isPressed) Color.White else accentColor, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressChanged(true)
                        tryAwaitRelease()
                        isPressed = false
                        onPressChanged(false)
                    }
                )
            }
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) DeepNavy else TextPrimary
        )
    }
}
