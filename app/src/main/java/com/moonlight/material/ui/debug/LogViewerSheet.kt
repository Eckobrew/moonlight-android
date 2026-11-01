package com.moonlight.material.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.material.R
import com.moonlight.material.core.utils.AppLogger
import com.moonlight.material.core.utils.LogLevel
import com.moonlight.material.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by AppLogger.logs.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }

    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        logs.filter { entry ->
            (selectedLevel == null || entry.level == selectedLevel) &&
            (searchQuery.isBlank() || entry.message.contains(searchQuery, ignoreCase = true) || entry.category.contains(searchQuery, ignoreCase = true))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MidnightBlue,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.logs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        val allText = logs.joinToString("\n") { it.toString() }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Moonlight Logs", allText))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = CyanAccent)
                    }

                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = RoseError)
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.filter_logs), color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardNavy,
                    unfocusedContainerColor = CardNavy,
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = CardNavy
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Log Level Filter Chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedLevel == null,
                    onClick = { selectedLevel = null },
                    label = { Text("All") }
                )
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = selectedLevel == level,
                        onClick = { selectedLevel = if (selectedLevel == level) null else level },
                        label = { Text(level.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Logs List
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DeepNavy,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { entry ->
                        val levelColor = when (entry.level) {
                            LogLevel.DEBUG -> TextSecondary
                            LogLevel.INFO -> CyanAccent
                            LogLevel.WARN -> AmberWarning
                            LogLevel.ERROR -> RoseError
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${entry.formattedTime} [${entry.category}]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = levelColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(130.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
