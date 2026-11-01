package com.moonlight.material.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonlight.material.R
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.state.AppState
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGridScreen(
    host: Host,
    appState: AppState,
    onLaunchApp: (StreamApp) -> Unit
) {
    BackHandler { appState.deselectHost() }
    val error by appState.appsError.collectAsState()
    val apps by appState.appsForSelectedHost.collectAsState()
    val isLoading by appState.isLoadingApps.collectAsState()
    var shortcutApp by remember { mutableStateOf<StreamApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    val runningApp = remember(apps, host) {
        apps.find { it.matchesRunning(host) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = host.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { appState.deselectHost() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(enabled = !isLoading, onClick = { appState.loadApps(host) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                    IconButton(onClick = { appState.isShowingSettings.value = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
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
            // Running App Banner
            if (runningApp != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardNavy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Currently Streaming",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent
                            )
                            Text(
                                text = runningApp.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onLaunchApp(runningApp) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = CyanAccent)
                            ) {
                                Text("Resume", color = DeepNavy, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { appState.quitRunningAppOnHost(host) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError)
                            ) {
                                Text("Quit")
                            }
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_apps), color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null, tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardNavy,
                    unfocusedContainerColor = CardNavy,
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = CardNavy
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (error != null) {
                Text(error!!, color = RoseError, modifier = Modifier.padding(16.dp))
            }
            if (isLoading && apps.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CyanAccent)
            }
            if (isLoading && apps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyanAccent)
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (error != null) "Unable to load applications. Tap Refresh to retry."
                            else if (searchQuery.isNotBlank()) "No matching applications"
                            else stringResource(R.string.no_apps_found),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                }
            } else {
                val context = androidx.compose.ui.platform.LocalContext.current
                val currentSettings by appState.settings.collectAsState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredApps, key = { it.stableIdentity }) { app ->
                        AppCard(
                            host = host,
                            app = app,
                            boxArtManager = appState.boxArtManager,
                            onLaunch = { onLaunchApp(app) },
                            onConfigureOverrides = {
                                appState.perAppSettingsApp.value = app
                            },
                            onPinShortcut = { shortcutApp = app }
                        )
                    }
                }
            }
        }
    }
    shortcutApp?.let { app ->
        ShortcutIconPickerSheet(host, app, appState.boxArtManager,
            onStatus = { appState.hostActionMessage.value = it },
            onDismiss = { shortcutApp = null })
    }
}
