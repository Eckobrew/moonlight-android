package com.moonlight.material.ui.stream

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.stream.input.ControllerButtonFlags
import com.moonlight.material.stream.input.GamepadListener
import com.moonlight.material.ui.theme.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class OscElementId(
    val key: String,
    val label: String,
    val defaultNormX: Float,
    val defaultNormY: Float
) {
    LEFT_BUMPERS("left_bumpers", "LT / LB / L3", 0.13f, 0.12f),
    RIGHT_BUMPERS("right_bumpers", "R3 / RB / RT", 0.87f, 0.12f),
    CENTER_BUTTONS("center_buttons", "Select / Guide / Start", 0.50f, 0.10f),
    DPAD("dpad", "D-Pad", 0.12f, 0.44f),
    LEFT_STICK("left_stick", "Left Stick", 0.23f, 0.78f),
    RIGHT_STICK("right_stick", "Right Stick", 0.75f, 0.78f),
    FACE_BUTTONS("face_buttons", "A / B / X / Y", 0.88f, 0.44f)
}

private data class OscElementPlacement(
    val normX: Float,
    val normY: Float,
    val scale: Float = 1.0f
)

private class OscLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("VirtualControllerLayout", Context.MODE_PRIVATE)

    fun load(id: OscElementId): OscElementPlacement {
        val nx = prefs.getFloat("${id.key}_nx", id.defaultNormX).coerceIn(0.04f, 0.96f)
        val ny = prefs.getFloat("${id.key}_ny", id.defaultNormY).coerceIn(0.05f, 0.95f)
        val scale = prefs.getFloat("${id.key}_scale", 1.0f).coerceIn(0.65f, 1.50f)
        return OscElementPlacement(nx, ny, scale)
    }

    fun save(id: OscElementId, placement: OscElementPlacement) {
        prefs.edit()
            .putFloat("${id.key}_nx", placement.normX.coerceIn(0.04f, 0.96f))
            .putFloat("${id.key}_ny", placement.normY.coerceIn(0.05f, 0.95f))
            .putFloat("${id.key}_scale", placement.scale.coerceIn(0.65f, 1.50f))
            .apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }
}

@Composable
fun VirtualTouchOverlay(
    opacity: Float = 0.65f,
    swapFaceButtons: Boolean = false,
    hapticsEnabled: Boolean = true,
    showQuickToggleButton: Boolean = true,
    listener: GamepadListener,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutStore = remember(context) { OscLayoutStore(context) }

    val placements = remember {
        mutableStateMapOf<OscElementId, OscElementPlacement>().apply {
            OscElementId.entries.forEach { put(it, layoutStore.load(it)) }
        }
    }

    var controlsTemporarilyHidden by remember { mutableStateOf(false) }
    var isEditingLayout by remember { mutableStateOf(false) }
    var selectedElement by remember { mutableStateOf<OscElementId?>(OscElementId.FACE_BUTTONS) }

    // Separate bitmasks per cluster so multi-touch and thumb roll-over never clobber other clusters
    var shoulderButtonsMask by remember { mutableIntStateOf(0) }
    var centerButtonsMask by remember { mutableIntStateOf(0) }
    var dpadButtonsMask by remember { mutableIntStateOf(0) }
    var faceButtonsMask by remember { mutableIntStateOf(0) }
    var leftStickClickMask by remember { mutableIntStateOf(0) }
    var rightStickClickMask by remember { mutableIntStateOf(0) }

    var leftTrigger by remember { mutableStateOf<Byte>(0) }
    var rightTrigger by remember { mutableStateOf<Byte>(0) }
    var leftStickX by remember { mutableStateOf<Short>(0) }
    var leftStickY by remember { mutableStateOf<Short>(0) }
    var rightStickX by remember { mutableStateOf<Short>(0) }
    var rightStickY by remember { mutableStateOf<Short>(0) }

    fun triggerHaptic(isRelease: Boolean = false) {
        if (!hapticsEnabled) return
        val constant = if (isRelease) {
            HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    fun emitControllerState() {
        val combinedButtons = shoulderButtonsMask or
            centerButtonsMask or
            dpadButtonsMask or
            faceButtonsMask or
            leftStickClickMask or
            rightStickClickMask
        listener.onControllerStateChanged(
            controllerNumber = 0,
            buttonFlags = combinedButtons,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY
        )
    }

    fun clearAllInputs() {
        shoulderButtonsMask = 0
        centerButtonsMask = 0
        dpadButtonsMask = 0
        faceButtonsMask = 0
        leftStickClickMask = 0
        rightStickClickMask = 0
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        emitControllerState()
    }

    DisposableEffect(listener) {
        onDispose {
            listener.onControllerStateChanged(0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val screenHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        // Top-Center Quick Show/Hide & Layout Customize Pill
        if (showQuickToggleButton || isEditingLayout || controlsTemporarilyHidden) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MidnightBlue.copy(alpha = if (isEditingLayout) 0.92f else 0.55f))
                    .border(
                        width = 1.dp,
                        color = if (isEditingLayout) CyanAccent else Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hide / Show Controls Pill Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            triggerHaptic()
                            if (isEditingLayout) {
                                isEditingLayout = false
                            } else {
                                controlsTemporarilyHidden = !controlsTemporarilyHidden
                                if (controlsTemporarilyHidden) clearAllInputs()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (controlsTemporarilyHidden) Icons.Default.VisibilityOff else Icons.Default.SportsEsports,
                        contentDescription = "Toggle Virtual Controller",
                        tint = if (controlsTemporarilyHidden) TextSecondary else CyanAccent,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (controlsTemporarilyHidden) "Show Pad" else "Pad",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (controlsTemporarilyHidden) TextSecondary else TextPrimary
                    )
                }

                // Edit Layout toggle button (when pad is visible)
                if (!controlsTemporarilyHidden) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isEditingLayout) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable {
                                triggerHaptic()
                                clearAllInputs()
                                isEditingLayout = !isEditingLayout
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isEditingLayout) Icons.Default.Check else Icons.Default.Tune,
                            contentDescription = "Customize Layout",
                            tint = if (isEditingLayout) CyanAccent else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isEditingLayout) "Done" else "Layout",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEditingLayout) CyanAccent else TextSecondary
                        )
                    }
                }
            }
        }

        // Layout Editor Banner (Scale + Reset controls for selected element)
        AnimatedVisibility(
            visible = isEditingLayout && !controlsTemporarilyHidden,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 42.dp)
        ) {
            val activeId = selectedElement ?: OscElementId.FACE_BUTTONS
            val activePlacement = placements[activeId] ?: OscElementPlacement(activeId.defaultNormX, activeId.defaultNormY, 1.0f)

            Surface(
                color = MidnightBlue.copy(alpha = 0.92f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Drag any cluster • ${activeId.label} (${(activePlacement.scale * 100).roundToInt()}%)",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Scale Down
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(CardNavy)
                            .clickable {
                                triggerHaptic()
                                val updated = activePlacement.copy(scale = (activePlacement.scale - 0.10f).coerceIn(0.65f, 1.50f))
                                placements[activeId] = updated
                                layoutStore.save(activeId, updated)
                            }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Smaller", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }

                    // Scale Up
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(CardNavy)
                            .clickable {
                                triggerHaptic()
                                val updated = activePlacement.copy(scale = (activePlacement.scale + 0.10f).coerceIn(0.65f, 1.50f))
                                placements[activeId] = updated
                                layoutStore.save(activeId, updated)
                            }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Larger", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }

                    // Reset All
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(RoseError.copy(alpha = 0.2f))
                            .clickable {
                                triggerHaptic()
                                layoutStore.resetAll()
                                OscElementId.entries.forEach { id ->
                                    placements[id] = OscElementPlacement(id.defaultNormX, id.defaultNormY, 1.0f)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Layout", tint = RoseError, modifier = Modifier.size(14.dp))
                        Text("Reset", color = RoseError, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (!controlsTemporarilyHidden) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isEditingLayout) 0.95f else opacity)
            ) {
                // 1. Left Bumpers & Triggers (LT, LB, L3)
                OscDraggableCluster(
                    id = OscElementId.LEFT_BUMPERS,
                    placement = placements[OscElementId.LEFT_BUMPERS]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 196.dp,
                    baseHeight = 44.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.LEFT_BUMPERS,
                    onSelect = { selectedElement = OscElementId.LEFT_BUMPERS },
                    onPlacementChanged = {
                        placements[OscElementId.LEFT_BUMPERS] = it
                        layoutStore.save(OscElementId.LEFT_BUMPERS, it)
                    }
                ) { scale ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TouchShoulderButton(
                            label = "LT",
                            width = (62 * scale).dp,
                            height = (40 * scale).dp,
                            isTrigger = true,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            leftTrigger = if (isDown) 255.toByte() else 0.toByte()
                            emitControllerState()
                        }
                        TouchShoulderButton(
                            label = "LB",
                            width = (62 * scale).dp,
                            height = (38 * scale).dp,
                            isTrigger = false,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            shoulderButtonsMask = if (isDown) {
                                shoulderButtonsMask or ControllerButtonFlags.LB
                            } else {
                                shoulderButtonsMask and ControllerButtonFlags.LB.inv()
                            }
                            emitControllerState()
                        }
                        TouchShoulderButton(
                            label = "L3",
                            width = (48 * scale).dp,
                            height = (34 * scale).dp,
                            isTrigger = false,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            shoulderButtonsMask = if (isDown) {
                                shoulderButtonsMask or ControllerButtonFlags.LS_CLK
                            } else {
                                shoulderButtonsMask and ControllerButtonFlags.LS_CLK.inv()
                            }
                            emitControllerState()
                        }
                    }
                }

                // 2. Center Utility Buttons (Select, Guide/Home, Start)
                OscDraggableCluster(
                    id = OscElementId.CENTER_BUTTONS,
                    placement = placements[OscElementId.CENTER_BUTTONS]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 192.dp,
                    baseHeight = 36.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.CENTER_BUTTONS,
                    onSelect = { selectedElement = OscElementId.CENTER_BUTTONS },
                    onPlacementChanged = {
                        placements[OscElementId.CENTER_BUTTONS] = it
                        layoutStore.save(OscElementId.CENTER_BUTTONS, it)
                    }
                ) { scale ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TouchPillButton(
                            label = "SELECT",
                            width = (58 * scale).dp,
                            height = (30 * scale).dp,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            centerButtonsMask = if (isDown) {
                                centerButtonsMask or ControllerButtonFlags.BACK
                            } else {
                                centerButtonsMask and ControllerButtonFlags.BACK.inv()
                            }
                            emitControllerState()
                        }
                        TouchPillButton(
                            label = "GUIDE",
                            width = (52 * scale).dp,
                            height = (30 * scale).dp,
                            accent = CyanAccent,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            centerButtonsMask = if (isDown) {
                                centerButtonsMask or ControllerButtonFlags.SPECIAL
                            } else {
                                centerButtonsMask and ControllerButtonFlags.SPECIAL.inv()
                            }
                            emitControllerState()
                        }
                        TouchPillButton(
                            label = "START",
                            width = (58 * scale).dp,
                            height = (30 * scale).dp,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            centerButtonsMask = if (isDown) {
                                centerButtonsMask or ControllerButtonFlags.START
                            } else {
                                centerButtonsMask and ControllerButtonFlags.START.inv()
                            }
                            emitControllerState()
                        }
                    }
                }

                // 3. Right Bumpers & Triggers (R3, RB, RT)
                OscDraggableCluster(
                    id = OscElementId.RIGHT_BUMPERS,
                    placement = placements[OscElementId.RIGHT_BUMPERS]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 196.dp,
                    baseHeight = 44.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.RIGHT_BUMPERS,
                    onSelect = { selectedElement = OscElementId.RIGHT_BUMPERS },
                    onPlacementChanged = {
                        placements[OscElementId.RIGHT_BUMPERS] = it
                        layoutStore.save(OscElementId.RIGHT_BUMPERS, it)
                    }
                ) { scale ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TouchShoulderButton(
                            label = "R3",
                            width = (48 * scale).dp,
                            height = (34 * scale).dp,
                            isTrigger = false,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            shoulderButtonsMask = if (isDown) {
                                shoulderButtonsMask or ControllerButtonFlags.RS_CLK
                            } else {
                                shoulderButtonsMask and ControllerButtonFlags.RS_CLK.inv()
                            }
                            emitControllerState()
                        }
                        TouchShoulderButton(
                            label = "RB",
                            width = (62 * scale).dp,
                            height = (38 * scale).dp,
                            isTrigger = false,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            shoulderButtonsMask = if (isDown) {
                                shoulderButtonsMask or ControllerButtonFlags.RB
                            } else {
                                shoulderButtonsMask and ControllerButtonFlags.RB.inv()
                            }
                            emitControllerState()
                        }
                        TouchShoulderButton(
                            label = "RT",
                            width = (62 * scale).dp,
                            height = (40 * scale).dp,
                            isTrigger = true,
                            isEditing = isEditingLayout
                        ) { isDown ->
                            triggerHaptic(!isDown)
                            rightTrigger = if (isDown) 255.toByte() else 0.toByte()
                            emitControllerState()
                        }
                    }
                }

                // 4. 8-Way D-Pad with smooth thumb slide/roll-over
                OscDraggableCluster(
                    id = OscElementId.DPAD,
                    placement = placements[OscElementId.DPAD]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 132.dp,
                    baseHeight = 132.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.DPAD,
                    onSelect = { selectedElement = OscElementId.DPAD },
                    onPlacementChanged = {
                        placements[OscElementId.DPAD] = it
                        layoutStore.save(OscElementId.DPAD, it)
                    }
                ) { scale ->
                    VirtualEightWayDpad(
                        size = (132 * scale).dp,
                        isEditing = isEditingLayout,
                        onDirectionMaskChanged = { newMask ->
                            if (newMask != dpadButtonsMask) {
                                triggerHaptic(newMask == 0)
                                dpadButtonsMask = newMask
                                emitControllerState()
                            }
                        }
                    )
                }

                // 5. Left Analog Stick (Floating center + double-tap L3 click)
                OscDraggableCluster(
                    id = OscElementId.LEFT_STICK,
                    placement = placements[OscElementId.LEFT_STICK]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 144.dp,
                    baseHeight = 144.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.LEFT_STICK,
                    onSelect = { selectedElement = OscElementId.LEFT_STICK },
                    onPlacementChanged = {
                        placements[OscElementId.LEFT_STICK] = it
                        layoutStore.save(OscElementId.LEFT_STICK, it)
                    }
                ) { scale ->
                    VirtualAnalogStick(
                        label = "LS",
                        size = (144 * scale).dp,
                        isEditing = isEditingLayout,
                        onStickMove = { x, y ->
                            leftStickX = (x * 0x7FFE).toInt().toShort()
                            leftStickY = (-y * 0x7FFE).toInt().toShort()
                            emitControllerState()
                        },
                        onStickClickChanged = { clicked ->
                            val newMask = if (clicked) ControllerButtonFlags.LS_CLK else 0
                            if (newMask != leftStickClickMask) {
                                triggerHaptic(!clicked)
                                leftStickClickMask = newMask
                                emitControllerState()
                            }
                        }
                    )
                }

                // 6. Right Analog Stick (Floating center + double-tap R3 click)
                OscDraggableCluster(
                    id = OscElementId.RIGHT_STICK,
                    placement = placements[OscElementId.RIGHT_STICK]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 138.dp,
                    baseHeight = 138.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.RIGHT_STICK,
                    onSelect = { selectedElement = OscElementId.RIGHT_STICK },
                    onPlacementChanged = {
                        placements[OscElementId.RIGHT_STICK] = it
                        layoutStore.save(OscElementId.RIGHT_STICK, it)
                    }
                ) { scale ->
                    VirtualAnalogStick(
                        label = "RS",
                        size = (138 * scale).dp,
                        isEditing = isEditingLayout,
                        onStickMove = { x, y ->
                            rightStickX = (x * 0x7FFE).toInt().toShort()
                            rightStickY = (-y * 0x7FFE).toInt().toShort()
                            emitControllerState()
                        },
                        onStickClickChanged = { clicked ->
                            val newMask = if (clicked) ControllerButtonFlags.RS_CLK else 0
                            if (newMask != rightStickClickMask) {
                                triggerHaptic(!clicked)
                                rightStickClickMask = newMask
                                emitControllerState()
                            }
                        }
                    )
                }

                // 7. Face Buttons Diamond (A, B, X, Y) with thumb slide/roll-over
                OscDraggableCluster(
                    id = OscElementId.FACE_BUTTONS,
                    placement = placements[OscElementId.FACE_BUTTONS]!!,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    baseWidth = 152.dp,
                    baseHeight = 152.dp,
                    isEditing = isEditingLayout,
                    isSelected = selectedElement == OscElementId.FACE_BUTTONS,
                    onSelect = { selectedElement = OscElementId.FACE_BUTTONS },
                    onPlacementChanged = {
                        placements[OscElementId.FACE_BUTTONS] = it
                        layoutStore.save(OscElementId.FACE_BUTTONS, it)
                    }
                ) { scale ->
                    VirtualFaceButtonDiamond(
                        size = (152 * scale).dp,
                        swapFaceButtons = swapFaceButtons,
                        isEditing = isEditingLayout,
                        onFaceMaskChanged = { newMask ->
                            if (newMask != faceButtonsMask) {
                                triggerHaptic(newMask == 0)
                                faceButtonsMask = newMask
                                emitControllerState()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OscDraggableCluster(
    id: OscElementId,
    placement: OscElementPlacement,
    screenWidthPx: Float,
    screenHeightPx: Float,
    baseWidth: Dp,
    baseHeight: Dp,
    isEditing: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPlacementChanged: (OscElementPlacement) -> Unit,
    content: @Composable (scale: Float) -> Unit
) {
    val density = LocalDensity.current
    val scaledW = baseWidth * placement.scale
    val scaledH = baseHeight * placement.scale
    val widthPx = with(density) { scaledW.toPx() }
    val heightPx = with(density) { scaledH.toPx() }

    val currentPlacement by rememberUpdatedState(placement)
    val currentOnPlacementChanged by rememberUpdatedState(onPlacementChanged)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val offsetX = (placement.normX * screenWidthPx - widthPx / 2f)
        .coerceIn(0f, (screenWidthPx - widthPx).coerceAtLeast(0f))
    val offsetY = (placement.normY * screenHeightPx - heightPx / 2f)
        .coerceIn(0f, (screenHeightPx - heightPx).coerceAtLeast(0f))

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(scaledW, scaledH)
            .then(
                if (isEditing) {
                    Modifier
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) CyanAccent else Color.White.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .background(
                            color = if (isSelected) CyanAccent.copy(alpha = 0.12f) else MidnightBlue.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .pointerInput(id, screenWidthPx, screenHeightPx) {
                            detectDragGestures(
                                onDragStart = { currentOnSelect() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val p = currentPlacement
                                    val newNx = (p.normX + dragAmount.x / screenWidthPx).coerceIn(0.04f, 0.96f)
                                    val newNy = (p.normY + dragAmount.y / screenHeightPx).coerceIn(0.05f, 0.95f)
                                    currentOnPlacementChanged(p.copy(normX = newNx, normY = newNy))
                                }
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        content(placement.scale)
    }
}

/**
 * Floating-center Virtual Analog Stick:
 * - Anchors the stick origin where the user's thumb initially touches down inside the ring,
 *   eliminating sudden full-tilt jumps when touching slightly off-center.
 * - Double-tap & hold inside the stick triggers L3/R3 stick click (just like upstream's analog stick).
 */
@Composable
private fun VirtualAnalogStick(
    label: String,
    size: Dp,
    isEditing: Boolean,
    onStickMove: (Float, Float) -> Unit,
    onStickClickChanged: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isActive by remember { mutableStateOf(false) }
    var isClicked by remember { mutableStateOf(false) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    val currentOnStickMove by rememberUpdatedState(onStickMove)
    val currentOnStickClickChanged by rememberUpdatedState(onStickClickChanged)

    val sizePx = with(density) { size.toPx() }
    val maxRadius = (sizePx * 0.36f).coerceAtLeast(24f)
    val knobSize = size * 0.42f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MidnightBlue.copy(alpha = if (isActive) 0.65f else 0.42f))
            .border(
                width = if (isClicked) 2.5.dp else 1.8.dp,
                color = when {
                    isClicked -> EmeraldGreen
                    isActive -> CyanAccent
                    else -> Color.White.copy(alpha = 0.28f)
                },
                shape = CircleShape
            )
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(maxRadius) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()

                            val now = android.os.SystemClock.uptimeMillis()
                            val doubleTapped = (now - lastTapTimeMs) <= 280L
                            lastTapTimeMs = now

                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            // Blend initial touch point toward center so minor off-center touches feel natural
                            val anchor = Offset(
                                center.x + (down.position.x - center.x) * 0.45f,
                                center.y + (down.position.y - center.y) * 0.45f
                            )

                            isActive = true
                            if (doubleTapped) {
                                isClicked = true
                                currentOnStickClickChanged(true)
                            }

                            fun updateFromPosition(pos: Offset) {
                                val rawDelta = pos - anchor
                                val dist = hypot(rawDelta.x, rawDelta.y)
                                knobOffset = if (dist > maxRadius) {
                                    Offset((rawDelta.x / dist) * maxRadius, (rawDelta.y / dist) * maxRadius)
                                } else {
                                    rawDelta
                                }
                                val normX = (knobOffset.x / maxRadius).coerceIn(-1f, 1f)
                                val normY = (knobOffset.y / maxRadius).coerceIn(-1f, 1f)
                                // Apply subtle 6% radial inner deadzone so resting thumb doesn't drift
                                val mag = hypot(normX, normY)
                                if (mag < 0.06f) {
                                    currentOnStickMove(0f, 0f)
                                } else {
                                    val rescaled = ((mag - 0.06f) / 0.94f).coerceIn(0f, 1f)
                                    currentOnStickMove((normX / mag) * rescaled, (normY / mag) * rescaled)
                                }
                            }

                            updateFromPosition(down.position)

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (pointer == null || !pointer.pressed) {
                                    break
                                }
                                pointer.consume()
                                updateFromPosition(pointer.position)
                            }

                            knobOffset = Offset.Zero
                            isActive = false
                            if (isClicked) {
                                isClicked = false
                                currentOnStickClickChanged(false)
                            }
                            currentOnStickMove(0f, 0f)
                        }
                    }
                } else Modifier
            )
    ) {
        // Crosshair guide lines
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val guideColor = Color.White.copy(alpha = 0.10f)
            drawCircle(color = guideColor, radius = this.size.minDimension * 0.36f, center = c, style = Stroke(width = 1.dp.toPx()))
        }

        // Thumb Stick Cap
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(knobSize)
                .clip(CircleShape)
                .background(
                    when {
                        isClicked -> EmeraldGreen.copy(alpha = 0.88f)
                        isActive -> CyanAccent.copy(alpha = 0.88f)
                        else -> CardNavy.copy(alpha = 0.82f)
                    }
                )
                .border(
                    width = 1.8.dp,
                    color = if (isActive || isClicked) Color.White else CyanAccent.copy(alpha = 0.65f),
                    shape = CircleShape
                )
        ) {
            Text(
                text = if (isClicked) "${label}3" else label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive || isClicked) DeepNavy else TextSecondary
            )
        }
    }
}

/**
 * 8-Way D-Pad with continuous thumb slide/roll-over across cardinals and diagonals.
 */
@Composable
private fun VirtualEightWayDpad(
    size: Dp,
    isEditing: Boolean,
    onDirectionMaskChanged: (Int) -> Unit
) {
    var activeMask by remember { mutableIntStateOf(0) }
    val currentOnMaskChanged by rememberUpdatedState(onDirectionMaskChanged)

    fun maskForPosition(pos: Offset, boxSize: Float): Int {
        val center = boxSize / 2f
        val dx = pos.x - center
        val dy = pos.y - center
        val dist = hypot(dx, dy)
        val deadzone = boxSize * 0.13f
        if (dist < deadzone) return 0

        // Angle in degrees [-180..180], where 0 is Right, -90 is Up, 90 is Down
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // 8 sectors of 45 degrees each (22.5 deg half-width)
        return when {
            angle >= -22.5f && angle < 22.5f -> ControllerButtonFlags.RIGHT
            angle >= 22.5f && angle < 67.5f -> ControllerButtonFlags.RIGHT or ControllerButtonFlags.DOWN
            angle >= 67.5f && angle < 112.5f -> ControllerButtonFlags.DOWN
            angle >= 112.5f && angle < 157.5f -> ControllerButtonFlags.DOWN or ControllerButtonFlags.LEFT
            angle >= 157.5f || angle < -157.5f -> ControllerButtonFlags.LEFT
            angle >= -157.5f && angle < -112.5f -> ControllerButtonFlags.LEFT or ControllerButtonFlags.UP
            angle >= -112.5f && angle < -67.5f -> ControllerButtonFlags.UP
            angle >= -67.5f && angle < -22.5f -> ControllerButtonFlags.UP or ControllerButtonFlags.RIGHT
            else -> 0
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val boxSize = this.size.width.toFloat().coerceAtLeast(1f)

                            val initial = maskForPosition(down.position, boxSize)
                            activeMask = initial
                            currentOnMaskChanged(initial)

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (pointer == null || !pointer.pressed) break
                                pointer.consume()
                                val updated = maskForPosition(pointer.position, boxSize)
                                if (updated != activeMask) {
                                    activeMask = updated
                                    currentOnMaskChanged(updated)
                                }
                            }

                            activeMask = 0
                            currentOnMaskChanged(0)
                        }
                    }
                } else Modifier
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val armThickness = w * 0.34f
            val armCorner = CornerRadius(w * 0.08f, w * 0.08f)
            val halfArm = armThickness / 2f
            val cx = w / 2f
            val cy = h / 2f

            val baseFill = MidnightBlue.copy(alpha = 0.58f)
            val borderStroke = Color.White.copy(alpha = 0.28f)

            // Draw Cross Background
            drawRoundRect(
                color = baseFill,
                topLeft = Offset(cx - halfArm, 0f),
                size = Size(armThickness, h),
                cornerRadius = armCorner
            )
            drawRoundRect(
                color = baseFill,
                topLeft = Offset(0f, cy - halfArm),
                size = Size(w, armThickness),
                cornerRadius = armCorner
            )

            // Highlight active directions
            val highlightColor = CyanAccent.copy(alpha = 0.85f)
            if ((activeMask and ControllerButtonFlags.UP) != 0) {
                drawRoundRect(
                    color = highlightColor,
                    topLeft = Offset(cx - halfArm, 0f),
                    size = Size(armThickness, cy),
                    cornerRadius = armCorner
                )
            }
            if ((activeMask and ControllerButtonFlags.DOWN) != 0) {
                drawRoundRect(
                    color = highlightColor,
                    topLeft = Offset(cx - halfArm, cy),
                    size = Size(armThickness, cy),
                    cornerRadius = armCorner
                )
            }
            if ((activeMask and ControllerButtonFlags.LEFT) != 0) {
                drawRoundRect(
                    color = highlightColor,
                    topLeft = Offset(0f, cy - halfArm),
                    size = Size(cx, armThickness),
                    cornerRadius = armCorner
                )
            }
            if ((activeMask and ControllerButtonFlags.RIGHT) != 0) {
                drawRoundRect(
                    color = highlightColor,
                    topLeft = Offset(cx, cy - halfArm),
                    size = Size(cx, armThickness),
                    cornerRadius = armCorner
                )
            }

            // Direction Triangles
            val arrowColorUp = if ((activeMask and ControllerButtonFlags.UP) != 0) DeepNavy else Color.White.copy(alpha = 0.75f)
            val arrowColorDown = if ((activeMask and ControllerButtonFlags.DOWN) != 0) DeepNavy else Color.White.copy(alpha = 0.75f)
            val arrowColorLeft = if ((activeMask and ControllerButtonFlags.LEFT) != 0) DeepNavy else Color.White.copy(alpha = 0.75f)
            val arrowColorRight = if ((activeMask and ControllerButtonFlags.RIGHT) != 0) DeepNavy else Color.White.copy(alpha = 0.75f)

            val pad = w * 0.11f
            val triW = w * 0.09f

            // Up triangle
            drawPath(
                path = Path().apply {
                    moveTo(cx, pad)
                    lineTo(cx - triW, pad + triW * 1.2f)
                    lineTo(cx + triW, pad + triW * 1.2f)
                    close()
                },
                color = arrowColorUp
            )
            // Down triangle
            drawPath(
                path = Path().apply {
                    moveTo(cx, h - pad)
                    lineTo(cx - triW, h - pad - triW * 1.2f)
                    lineTo(cx + triW, h - pad - triW * 1.2f)
                    close()
                },
                color = arrowColorDown
            )
            // Left triangle
            drawPath(
                path = Path().apply {
                    moveTo(pad, cy)
                    lineTo(pad + triW * 1.2f, cy - triW)
                    lineTo(pad + triW * 1.2f, cy + triW)
                    close()
                },
                color = arrowColorLeft
            )
            // Right triangle
            drawPath(
                path = Path().apply {
                    moveTo(w - pad, cy)
                    lineTo(w - pad - triW * 1.2f, cy - triW)
                    lineTo(w - pad - triW * 1.2f, cy + triW)
                    close()
                },
                color = arrowColorRight
            )

            // Center pivot circle
            drawCircle(
                color = borderStroke,
                radius = w * 0.10f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

/**
 * Face Button Diamond (A, B, X, Y) with continuous multi-touch & thumb roll-over:
 * Users can slide a single thumb smoothly from X -> A or Y -> B (or hold two buttons in chord)
 * without lifting their thumb.
 */
@Composable
private fun VirtualFaceButtonDiamond(
    size: Dp,
    swapFaceButtons: Boolean,
    isEditing: Boolean,
    onFaceMaskChanged: (Int) -> Unit
) {
    val aFlag = if (swapFaceButtons) ControllerButtonFlags.B else ControllerButtonFlags.A
    val bFlag = if (swapFaceButtons) ControllerButtonFlags.A else ControllerButtonFlags.B
    val xFlag = if (swapFaceButtons) ControllerButtonFlags.Y else ControllerButtonFlags.X
    val yFlag = if (swapFaceButtons) ControllerButtonFlags.X else ControllerButtonFlags.Y

    val aLabel = if (swapFaceButtons) "B" else "A"
    val bLabel = if (swapFaceButtons) "A" else "B"
    val xLabel = if (swapFaceButtons) "Y" else "X"
    val yLabel = if (swapFaceButtons) "X" else "Y"

    var activeMask by remember { mutableIntStateOf(0) }
    val currentOnFaceMaskChanged by rememberUpdatedState(onFaceMaskChanged)

    val buttonSize = size * 0.34f

    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(swapFaceButtons) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()

                            val w = this.size.width.toFloat().coerceAtLeast(1f)
                            val h = this.size.height.toFloat().coerceAtLeast(1f)
                            val hitRadius = w * 0.24f

                            val topCenter = Offset(w * 0.50f, h * 0.17f)    // Y
                            val bottomCenter = Offset(w * 0.50f, h * 0.83f) // A
                            val leftCenter = Offset(w * 0.17f, h * 0.50f)   // X
                            val rightCenter = Offset(w * 0.83f, h * 0.50f)  // B

                            fun maskForPointers(positions: List<Offset>): Int {
                                var m = 0
                                for (p in positions) {
                                    if (hypot(p.x - topCenter.x, p.y - topCenter.y) <= hitRadius) m = m or yFlag
                                    if (hypot(p.x - bottomCenter.x, p.y - bottomCenter.y) <= hitRadius) m = m or aFlag
                                    if (hypot(p.x - leftCenter.x, p.y - leftCenter.y) <= hitRadius) m = m or xFlag
                                    if (hypot(p.x - rightCenter.x, p.y - rightCenter.y) <= hitRadius) m = m or bFlag
                                }
                                return m
                            }

                            val initial = maskForPointers(listOf(down.position))
                            activeMask = initial
                            currentOnFaceMaskChanged(initial)

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedPointers = event.changes.filter { it.pressed }
                                if (pressedPointers.isEmpty()) break
                                pressedPointers.forEach { it.consume() }
                                val updated = maskForPointers(pressedPointers.map { it.position })
                                if (updated != activeMask) {
                                    activeMask = updated
                                    currentOnFaceMaskChanged(updated)
                                }
                            }

                            activeMask = 0
                            currentOnFaceMaskChanged(0)
                        }
                    }
                } else Modifier
            )
    ) {
        // Top (Y)
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            FaceCircleVisual(
                label = yLabel,
                size = buttonSize,
                accentColor = AmberWarning,
                isPressed = (activeMask and yFlag) != 0
            )
        }
        // Bottom (A)
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            FaceCircleVisual(
                label = aLabel,
                size = buttonSize,
                accentColor = EmeraldGreen,
                isPressed = (activeMask and aFlag) != 0
            )
        }
        // Left (X)
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            FaceCircleVisual(
                label = xLabel,
                size = buttonSize,
                accentColor = CyanAccent,
                isPressed = (activeMask and xFlag) != 0
            )
        }
        // Right (B)
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            FaceCircleVisual(
                label = bLabel,
                size = buttonSize,
                accentColor = RoseError,
                isPressed = (activeMask and bFlag) != 0
            )
        }
    }
}

@Composable
private fun FaceCircleVisual(
    label: String,
    size: Dp,
    accentColor: Color,
    isPressed: Boolean
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isPressed) accentColor else MidnightBlue.copy(alpha = 0.62f))
            .border(
                width = if (isPressed) 2.5.dp else 2.dp,
                color = if (isPressed) Color.White else accentColor.copy(alpha = 0.85f),
                shape = CircleShape
            )
    ) {
        Text(
            text = label,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) DeepNavy else TextPrimary
        )
    }
}

@Composable
private fun TouchShoulderButton(
    label: String,
    width: Dp,
    height: Dp,
    isTrigger: Boolean,
    isEditing: Boolean,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnPressChanged by rememberUpdatedState(onPressChanged)
    val shape = RoundedCornerShape(
        topStart = if (isTrigger) 14.dp else 8.dp,
        topEnd = if (isTrigger) 14.dp else 8.dp,
        bottomStart = 8.dp,
        bottomEnd = 8.dp
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width, height)
            .clip(shape)
            .background(
                when {
                    isPressed -> CyanAccent
                    isTrigger -> MidnightBlue.copy(alpha = 0.68f)
                    else -> MidnightBlue.copy(alpha = 0.56f)
                }
            )
            .border(
                width = if (isPressed) 2.dp else 1.2.dp,
                color = if (isPressed) Color.White else CyanAccent.copy(alpha = if (isTrigger) 0.55f else 0.35f),
                shape = shape
            )
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(label) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                currentOnPressChanged(true)
                                tryAwaitRelease()
                                isPressed = false
                                currentOnPressChanged(false)
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) DeepNavy else TextPrimary
        )
    }
}

@Composable
private fun TouchPillButton(
    label: String,
    width: Dp,
    height: Dp,
    accent: Color = Color.White.copy(alpha = 0.32f),
    isEditing: Boolean,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnPressChanged by rememberUpdatedState(onPressChanged)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(50))
            .background(if (isPressed) CyanAccent else MidnightBlue.copy(alpha = 0.58f))
            .border(1.dp, if (isPressed) Color.White else accent, RoundedCornerShape(50))
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(label) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                currentOnPressChanged(true)
                                tryAwaitRelease()
                                isPressed = false
                                currentOnPressChanged(false)
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) DeepNavy else TextPrimary
        )
    }
}
