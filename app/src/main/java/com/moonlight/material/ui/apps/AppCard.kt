package com.moonlight.material.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.moonlight.material.R
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.ui.theme.*

@Composable
fun AppCard(
    host: Host,
    app: StreamApp,
    onLaunch: () -> Unit,
    onConfigureOverrides: () -> Unit
) {
    val isRunning = app.matchesRunning(host)

    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onLaunch)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f) // 2:3 game poster ratio
                .clip(RoundedCornerShape(14.dp))
                .background(CardNavy)
        ) {
            // Boxart image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(app.boxArtUrl)
                    .crossfade(true)
                    .build(),
                placeholder = painterResource(R.drawable.ic_boxart_placeholder),
                error = painterResource(R.drawable.ic_boxart_placeholder),
                contentDescription = app.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Running indicator
            if (isRunning) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyanAccent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = DeepNavy,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Running",
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepNavy,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Per-app settings shortcut button
            IconButton(
                onClick = onConfigureOverrides,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(DeepNavy.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Game Settings",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // App Title
        Text(
            text = app.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
