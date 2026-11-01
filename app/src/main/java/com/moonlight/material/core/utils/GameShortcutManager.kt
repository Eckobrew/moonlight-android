package com.moonlight.material.core.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.moonlight.material.MainActivity
import com.moonlight.material.core.models.*
import com.moonlight.material.core.state.AppSettingsStore
import com.moonlight.material.networking.HostWakeConnection
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class ShortcutLaunchRequest(val hostId: String, val serverId: String?, val appId: Int, val appUUID: String?, val title: String)
data class ResolvedShortcut(val host: Host, val app: StreamApp, val settings: StreamSettings)
enum class ShortcutPinResult { REQUESTED, UPDATED }

object GameShortcutManager {
    const val EXTRA_REQUEST = "EXTRA_SHORTCUT_REQUEST"
    private val json = Json { ignoreUnknownKeys = true }

    fun pinGameShortcut(context: Context, host: Host, app: StreamApp, icon: Bitmap): ShortcutPinResult {
        check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) { "This launcher does not support adding home-screen shortcuts." }
        require(!app.isHostAction) { "Only streaming applications can be added to the home screen." }
        val id = ShortcutBundleStore.identifier(host, app)
        val request = ShortcutLaunchRequest(host.id, host.serverId, app.id, app.uuid, app.title)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_REQUEST, json.encodeToString(request))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(app.title.take(40).ifBlank { "Game" })
            .setLongLabel("${app.title} on ${host.name}")
            .setIcon(IconCompat.createWithBitmap(icon)).setIntent(intent).build()
        if (context.getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.any { it.id == id } == true) {
            check(ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))) { "The launcher could not update this shortcut. Try again." }
            return ShortcutPinResult.UPDATED
        }
        check(ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) { "The launcher did not accept the shortcut request." }
        return ShortcutPinResult.REQUESTED
    }

    fun decodeRequest(value: String): ShortcutLaunchRequest = json.decodeFromString(value)

    fun findHost(request: ShortcutLaunchRequest, hosts: List<Host>): Host = hosts.firstOrNull {
        it.id == request.hostId || (!request.serverId.isNullOrBlank() && request.serverId.equals(it.serverId, true))
    } ?: throw IOException("This shortcut's PC is no longer saved. Add and pair with it again, then recreate the shortcut.")

    fun findApp(request: ShortcutLaunchRequest, apps: List<StreamApp>): StreamApp {
        val app = if (!request.appUUID.isNullOrBlank()) apps.firstOrNull { request.appUUID.equals(it.uuid, true) }
            else apps.firstOrNull { it.id == request.appId && it.title == request.title }
                ?: apps.filter { it.title == request.title }.singleOrNull()
        return app?.takeUnless { it.isHostAction }
            ?: throw IOException("${request.title} is no longer available on this PC. Refresh its apps and recreate the shortcut.")
    }

    suspend fun resolve(context: Context, request: ShortcutLaunchRequest, http: SunshineHTTPClient): ResolvedShortcut = withContext(Dispatchers.IO) {
        val store = AppSettingsStore(context)
        val saved = findHost(request, store.loadHosts())
        if (!saved.isSecurelyPaired) throw IOException("Pair with ${saved.name} in Moonlight before opening this shortcut.")
        val host = HostWakeConnection.resolve(saved, probe = { http.fetchServerInfo(it).first })
        val app = findApp(request, http.fetchAppList(host))
        val settings = store.loadAppOverrides(host.id, app.id).applyTo(store.loadSettings())
        ResolvedShortcut(host, app, settings)
    }
}
