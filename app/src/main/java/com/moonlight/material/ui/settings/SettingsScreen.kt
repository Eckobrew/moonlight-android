package com.moonlight.material.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.R
import com.moonlight.material.core.models.*
import com.moonlight.material.ui.theme.*

enum class SettingsTab(val title: String, val icon: ImageVector) {
    VIDEO("Display & Video", Icons.Default.Tv),
    AUDIO("Audio", Icons.Default.VolumeUp),
    INPUT("Input & Touch", Icons.Default.TouchApp),
    HOST("Host & Stream", Icons.Default.Dns),
    CONTROLLER("Controllers", Icons.Default.SportsEsports),
    APP("Application", Icons.Default.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentSettings: StreamSettings,
    onSave: (StreamSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var settings by remember { mutableStateOf(currentSettings) }
    var selectedTab by remember { mutableStateOf(SettingsTab.VIDEO) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            onSave(settings)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.done), color = DeepNavy, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepNavy)
            )
        },
        containerColor = DeepNavy
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scrollable Category Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MidnightBlue,
                contentColor = CyanAccent,
                edgePadding = 16.dp
            ) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(tab.icon, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tab.title)
                            }
                        }
                    )
                }
            }

            // Tab Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    SettingsTab.VIDEO -> VideoSettingsTab(settings) { settings = it }
                    SettingsTab.AUDIO -> AudioSettingsTab(settings) { settings = it }
                    SettingsTab.INPUT -> InputSettingsTab(settings) { settings = it }
                    SettingsTab.HOST -> HostSettingsTab(settings) { settings = it }
                    SettingsTab.CONTROLLER -> ControllerSettingsTab(settings) { settings = it }
                    SettingsTab.APP -> ApplicationSettingsTab(settings) { settings = it }
                }
            }
        }
    }
}

@Composable
private fun VideoSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "Resolution & Refresh Rate") {
        // Preset Resolutions
        val resolutions = listOf(
            Pair("720p (1280x720)", Pair(1280, 720)),
            Pair("1080p (1920x1080)", Pair(1920, 1080)),
            Pair("1440p (2560x1440)", Pair(2560, 1440)),
            Pair("4K UHD (3840x2160)", Pair(3840, 2160))
        )

        Text("Resolution", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            resolutions.forEach { (label, dims) ->
                val isSelected = settings.width == dims.first && settings.height == dims.second
                FilterChip(
                    selected = isSelected,
                    onClick = { onUpdate(settings.copy(width = dims.first, height = dims.second)) },
                    label = { Text(label.split(" ")[0]) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Frame rate
        val fpsList = listOf(30, 60, 90, 120, 144)
        Text("Frame Rate: ${settings.fps} FPS", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            fpsList.forEach { fpsVal ->
                FilterChip(
                    selected = settings.fps == fpsVal,
                    onClick = { onUpdate(settings.copy(fps = fpsVal)) },
                    label = { Text("${fpsVal}fps") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bitrate Slider
        Text("Bitrate: ${settings.bitrateMbps} Mbps", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Slider(
            value = settings.bitrateMbps.toFloat(),
            onValueChange = { onUpdate(settings.copy(bitrateMbps = it.toInt())) },
            valueRange = 1f..150f,
            steps = 149,
            colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Remote Bitrate Override
        SettingToggle(
            title = "Custom Bitrate for Remote Sessions",
            checked = settings.enableRemoteBitrate,
            onCheckedChange = { onUpdate(settings.copy(enableRemoteBitrate = it)) }
        )

        if (settings.enableRemoteBitrate) {
            Text("Remote Bitrate: ${if (settings.remoteBitrateMbps == 0) "Auto (25%)" else "${settings.remoteBitrateMbps} Mbps"}", color = TextSecondary)
            Slider(
                value = settings.remoteBitrateMbps.toFloat(),
                onValueChange = { onUpdate(settings.copy(remoteBitrateMbps = it.toInt())) },
                valueRange = 0f..100f
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SectionCard(title = "Codec & Rendering") {
        Text("Video Codec", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoCodec.entries.forEach { codecOption ->
                FilterChip(
                    selected = settings.codec == codecOption,
                    onClick = { onUpdate(settings.copy(codec = codecOption)) },
                    label = { Text(codecOption.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingToggle(
            title = "Enable HDR (10-bit High Dynamic Range)",
            checked = settings.enableHdr,
            onCheckedChange = { onUpdate(settings.copy(enableHdr = it)) }
        )

        SettingToggle(
            title = "Full Dynamic Range Color",
            checked = settings.fullRangeColor,
            onCheckedChange = { onUpdate(settings.copy(fullRangeColor = it)) }
        )

        SettingToggle(
            title = "Enable V-Sync",
            checked = settings.enableVsync,
            onCheckedChange = { onUpdate(settings.copy(enableVsync = it)) }
        )

        SettingToggle(
            title = "Enable Frame Pacing",
            checked = settings.enableFramePacing,
            onCheckedChange = { onUpdate(settings.copy(enableFramePacing = it)) }
        )

        SettingToggle(
            title = "YUV 4:4:4 Chroma Subsampling",
            checked = settings.features.yuv444,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(yuv444 = it))) }
        )
    }
}

@Composable
private fun AudioSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "Audio Output") {
        Text("Speaker Configuration", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioConfigurationMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.audioMode == mode,
                    onClick = { onUpdate(settings.copy(audioMode = mode)) },
                    label = { Text(mode.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingToggle(
            title = "Mute Host PC Speakers While Streaming",
            checked = settings.muteHostAudio,
            onCheckedChange = { onUpdate(settings.copy(muteHostAudio = it)) }
        )

        SettingToggle(
            title = "Mute Audio When in Background",
            checked = settings.muteAudioOnFocusLoss,
            onCheckedChange = { onUpdate(settings.copy(muteAudioOnFocusLoss = it)) }
        )
    }
}

@Composable
private fun InputSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "Mouse & Pointer") {
        SettingToggle(
            title = "Desktop Mouse Mode (Absolute Pointer)",
            checked = settings.features.desktopMouse,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(desktopMouse = it))) }
        )

        Text("Mouse Sensitivity: ${String.format("%.1f", settings.mouseSensitivityX)}x", color = TextSecondary)
        Slider(
            value = settings.mouseSensitivityX.toFloat(),
            onValueChange = { onUpdate(settings.copy(mouseSensitivityX = it.toDouble(), mouseSensitivityY = it.toDouble())) },
            valueRange = 0.2f..3.0f
        )

        SettingToggle(
            title = "Swap Left and Right Mouse Buttons",
            checked = settings.features.swapMouseButtons,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(swapMouseButtons = it))) }
        )

        SettingToggle(
            title = "Reverse Scroll Direction",
            checked = settings.features.reverseScroll,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(reverseScroll = it))) }
        )

        SettingToggle(
            title = "Swap Windows and Alt Keys",
            checked = settings.swapWinAltKeys,
            onCheckedChange = { onUpdate(settings.copy(swapWinAltKeys = it)) }
        )
    }
}

@Composable
private fun HostSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "Host Integration") {
        SettingToggle(
            title = "Optimize Game Settings (SOPS)",
            subtitle = "Allows host to automatically tune graphics for streaming",
            checked = settings.optimizeGameSettings,
            onCheckedChange = { onUpdate(settings.copy(optimizeGameSettings = it)) }
        )

        SettingToggle(
            title = "Quit Host App When Disconnecting",
            checked = settings.quitAppOnStop,
            onCheckedChange = { onUpdate(settings.copy(quitAppOnStop = it)) }
        )

        SettingToggle(
            title = "Clipboard Synchronization",
            subtitle = "Sync copied text securely between Android and host",
            checked = settings.enableClipboardSync,
            onCheckedChange = { onUpdate(settings.copy(enableClipboardSync = it)) }
        )

        SettingToggle(
            title = "Keep Screen Active (Wake Lock)",
            checked = settings.keepScreenActive,
            onCheckedChange = { onUpdate(settings.copy(keepScreenActive = it)) }
        )
    }
}

@Composable
private fun ControllerSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "On-Screen Touch Controller") {
        SettingToggle(
            title = "Virtual Touch Controller Overlay",
            subtitle = "Display touch sticks and buttons on screen",
            checked = settings.features.touchOverlayEnabled,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(touchOverlayEnabled = it))) }
        )

        if (settings.features.touchOverlayEnabled) {
            Text("Overlay Opacity: ${(settings.features.touchOverlayOpacity * 100).toInt()}%", color = TextSecondary)
            Slider(
                value = settings.features.touchOverlayOpacity,
                onValueChange = { onUpdate(settings.copy(features = settings.features.copy(touchOverlayOpacity = it))) },
                valueRange = 0.2f..1.0f
            )

            SettingToggle(
                title = "Touch Vibration / Haptics",
                checked = settings.features.touchHapticsEnabled,
                onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(touchHapticsEnabled = it))) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SectionCard(title = "Hardware Gamepad Options") {
        SettingToggle(
            title = "Controller Mouse Emulation",
            subtitle = "Hold Start for 1 second to toggle pointer emulation",
            checked = settings.features.gamepadMouse,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(gamepadMouse = it))) }
        )

        SettingToggle(
            title = "Swap Face Buttons (Nintendo A/B X/Y)",
            checked = settings.features.swapFaceButtons,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(swapFaceButtons = it))) }
        )

        SettingToggle(
            title = "Allow Gamepad in Background",
            checked = settings.features.backgroundGamepad,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(backgroundGamepad = it))) }
        )

        SettingToggle(
            title = "Persistent Gamepad Slot (Player 1)",
            checked = settings.features.persistentGamepad,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(persistentGamepad = it))) }
        )
    }
}

@Composable
private fun ApplicationSettingsTab(settings: StreamSettings, onUpdate: (StreamSettings) -> Unit) {
    SectionCard(title = "Discovery & Display") {
        SettingToggle(
            title = "Discover Hosts Automatically (mDNS)",
            checked = settings.features.enableMdns,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(enableMdns = it))) }
        )

        SettingToggle(
            title = "Hide Unpaired PCs",
            checked = settings.hideUnpairedHosts,
            onCheckedChange = { onUpdate(settings.copy(hideUnpairedHosts = it)) }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    SectionCard(title = "Discord Rich Presence") {
        SettingToggle(
            title = "Enable Discord Rich Presence",
            checked = settings.features.discordPresence,
            onCheckedChange = { onUpdate(settings.copy(features = settings.features.copy(discordPresence = it))) }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardNavy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CyanAccent)
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = DeepNavy, checkedTrackColor = CyanAccent)
        )
    }
}
