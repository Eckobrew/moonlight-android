package com.moonlight.material.ui.apps

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.utils.*
import com.moonlight.material.ui.theme.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShortcutIconPickerSheet(host: Host, app: StreamApp, boxArtManager: BoxArtManager,
    onStatus: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("moonlight_artwork", android.content.Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("steamgriddb_api_key", "").orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(app.title) }
    var results by remember { mutableStateOf(emptyList<ArtworkSearchResult>()) }
    var category by remember { mutableStateOf(ArtworkCategory.ALL) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hostArt by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var style by remember { mutableStateOf(ShortcutIconStyle.SQUIRCLE) }
    var fill by remember { mutableStateOf(ShortcutFillMode.CROP) }
    var backdrop by remember { mutableStateOf(ShortcutBackdrop.BLURRED) }
    var creating by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun select(source: String) { selectedSource = source; sourceBitmap = null; imageError = null }
    fun search() {
        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true; searchError = null
            try {
                prefs.edit().putString("steamgriddb_api_key", apiKey.trim()).apply()
                results = GameArtworkSearchService().searchArtwork(query, apiKey.trim())
                if (results.isEmpty()) searchError = "No matching artwork. Try a different name or choose a local image."
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                searchError = "Online artwork unavailable: ${e.message}"
            } finally { searching = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { select(it.toString()) } }
    LaunchedEffect(host.id, app.stableIdentity) {
        hostArt = boxArtManager.loadBoxArt(host, app)?.absolutePath
        if (selectedSource == null) hostArt?.let { select(it) }
        search()
    }
    LaunchedEffect(selectedSource) {
        sourceBitmap = null; imageError = null
        val source = selectedSource ?: return@LaunchedEffect
        try {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(source).size(1024).allowHardware(false).build())
            sourceBitmap = (result as? SuccessResult)?.drawable?.toBitmap()
                ?: throw java.io.IOException("This artwork could not be downloaded or decoded. Choose another image.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) { imageError = e.message }
    }
    val preview by produceState<Bitmap?>(null, sourceBitmap, style, fill, backdrop) {
        value = null
        val source = sourceBitmap ?: return@produceState
        value = withContext(Dispatchers.Default) { ShortcutIconRenderer.render(source, style, fill, backdrop) }
    }
    ModalBottomSheet(onDismissRequest = { if (!creating) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MidnightBlue) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Create Home-Screen Shortcut", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text("Choose artwork for ${app.title} on ${host.name}", color = TextSecondary)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(preview, "Shortcut preview", modifier = Modifier.size(128.dp), contentScale = ContentScale.Fit)
                Text(app.title, color = TextPrimary)
                Text("Your launcher may apply its own icon mask.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShortcutIconStyle.entries.forEach { value -> FilterChip(style == value, { style = value }, label = { Text(value.label) }) }
            }
            if (style == ShortcutIconStyle.CIRCLE || style == ShortcutIconStyle.SQUIRCLE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShortcutFillMode.entries.forEach { value -> FilterChip(fill == value, { fill = value }, label = { Text(value.label) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShortcutBackdrop.entries.forEach { value -> FilterChip(backdrop == value, { backdrop = value }, label = { Text(value.label) }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ hostArt?.let { select(it) } }, enabled = hostArt != null, modifier = Modifier.weight(1f)) { Text("Host Box Art") }
                OutlinedButton({ picker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Browse Image…") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search game artwork") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ search() }, enabled = query.isNotBlank() && !searching, modifier = Modifier.fillMaxWidth()) {
                Text(if (searching) "Searching…" else "Search")
            }
            TextButton({ showKey = !showKey }) { Text("SteamGridDB API Key (Optional)") }
            if (showKey) {
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Optional community artwork from steamgriddb.com. Steam artwork works without a key.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArtworkCategory.entries.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value.label) }) }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            searchError?.let { Text(it, color = AmberWarning) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(160.dp)) {
                items(results.filter { category == ArtworkCategory.ALL || it.category == category }, key = { it.imageUrl }) { result ->
                    OutlinedCard(onClick = { select(result.imageUrl) }, modifier = Modifier.width(110.dp),
                        border = BorderStroke(if (selectedSource == result.imageUrl) 2.dp else 1.dp,
                            if (selectedSource == result.imageUrl) CyanAccent else TextSecondary)) {
                        AsyncImage(result.imageUrl, result.title, modifier = Modifier.fillMaxWidth().height(116.dp), contentScale = ContentScale.Fit)
                        Text(result.source, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
                    }
                }
            }
            imageError?.let { Text(it, color = RoseError) }
            Button(onClick = {
                val bitmap = sourceBitmap ?: return@Button
                val chosenStyle = style; val chosenFill = fill; val chosenBackdrop = backdrop
                scope.launch {
                    creating = true; imageError = null
                    try {
                        val icon = withContext(Dispatchers.Default) { ShortcutIconRenderer.render(bitmap, chosenStyle, chosenFill, chosenBackdrop) }
                        val result = GameShortcutManager.pinGameShortcut(context, host, app, icon)
                        onDismiss()
                        onStatus(if (result == ShortcutPinResult.UPDATED) "Shortcut updated." else "Confirm adding the shortcut in your launcher.")
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { imageError = e.message ?: "Could not create the shortcut." }
                    finally { creating = false }
                }
            }, enabled = sourceBitmap != null && !creating, modifier = Modifier.fillMaxWidth()) {
                Text(if (creating) "Creating…" else "Add to Home Screen")
            }
            TextButton(onDismiss, enabled = !creating, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
