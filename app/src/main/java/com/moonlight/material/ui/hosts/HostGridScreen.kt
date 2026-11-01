package com.moonlight.material.ui.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moonlight.material.R
import com.moonlight.material.core.state.AppState
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostGridScreen(appState: AppState) {
    val hosts by appState.hosts.collectAsState()
    val settings by appState.settings.collectAsState()
    val isRefreshing by appState.isRefreshingHosts.collectAsState()

    val filteredHosts = remember(hosts, settings.hideUnpairedHosts) {
        if (settings.hideUnpairedHosts) {
            hosts.filter { it.isPaired }
        } else {
            hosts
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Add Host
                    IconButton(onClick = { appState.isShowingAddHost.value = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Host", tint = TextPrimary)
                    }
                    // Refresh
                    IconButton(onClick = { appState.refreshHosts() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                    // Logs
                    IconButton(onClick = { appState.isShowingLogs.value = true }) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = TextPrimary)
                    }
                    // Settings
                    IconButton(onClick = { appState.isShowingSettings.value = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepNavy
                )
            )
        },
        containerColor = DeepNavy
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (filteredHosts.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_hosts_found),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_hosts_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { appState.isShowingAddHost.value = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Icon(Icons.Default.Add, null, tint = DeepNavy)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.add_host_manually),
                            color = DeepNavy,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredHosts, key = { it.id }) { host ->
                        HostCard(
                            host = host,
                            onSelect = { appState.selectHost(host) },
                            onPair = { appState.startPairing(host) },
                            onWake = { appState.wakeHost(host) },
                            onDetails = { appState.hostDetailsToShow.value = host },
                            onUnpair = { appState.unpairHost(host) },
                            onDelete = { appState.deleteHost(host) }
                        )
                    }
                }
            }

            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = CyanAccent,
                    trackColor = DeepNavy
                )
            }
        }
    }
}
