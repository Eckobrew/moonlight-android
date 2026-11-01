package com.moonlight.material.ui.apps

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.ui.theme.*

/**
 * Bottom sheet for choosing an icon when creating an Android home screen shortcut.
 * Ported from Sources/MoonlightMac/UI/Apps/ShortcutIconPickerSheet.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutIconPickerSheet(
    app: StreamApp,
    selectedIconUrl: String?,
    onSelectIcon: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultIcons = listOf(
        Icons.Default.SportsEsports,
        Icons.Default.Gamepad,
        Icons.Default.VideogameAsset,
        Icons.Default.PlayArrow,
        Icons.Default.Tv
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MidnightBlue,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Shortcut Icon",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Choose an icon for ${app.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Box art option
            if (!app.boxArtUrl.isNullOrEmpty()) {
                Text(
                    text = "Game Box Art",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyanAccent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isBoxArtSelected = selectedIconUrl == app.boxArtUrl || selectedIconUrl == null
                Box(
                    modifier = Modifier
                        .size(100.dp, 140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (isBoxArtSelected) 2.dp else 1.dp,
                            color = if (isBoxArtSelected) CyanAccent else CardNavy,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            onSelectIcon(app.boxArtUrl)
                            onDismiss()
                        }
                ) {
                    AsyncImage(
                        model = app.boxArtUrl,
                        contentDescription = app.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isBoxArtSelected) {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            color = CyanAccent,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = DeepNavy,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = "Preset Icons",
                style = MaterialTheme.typography.labelLarge,
                color = CyanAccent,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                defaultIcons.forEachIndexed { index, icon ->
                    val identifier = "preset_$index"
                    val isSelected = selectedIconUrl == identifier
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) CyanAccent.copy(alpha = 0.2f) else CardNavy,
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) CyanAccent else CardNavy
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .clickable {
                                onSelectIcon(identifier)
                                onDismiss()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) CyanAccent else TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
