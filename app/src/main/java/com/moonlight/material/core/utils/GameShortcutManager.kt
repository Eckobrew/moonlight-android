package com.moonlight.material.core.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.moonlight.material.R
import com.moonlight.material.StreamActivity
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.models.StreamSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pins direct game launcher shortcuts to the Android home screen via ShortcutManagerCompat.
 * Matches GameShortcutManager.swift & ShortcutBundleStore.swift
 */
object GameShortcutManager {

    fun pinGameShortcut(
        context: Context,
        host: Host,
        app: StreamApp,
        settings: StreamSettings
    ): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return false
        }

        val shortcutId = ShortcutBundleStore.identifier(host, app)
        val json = Json { ignoreUnknownKeys = true }

        val intent = Intent(context, StreamActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_HOST", json.encodeToString(host))
            putExtra("EXTRA_APP", json.encodeToString(app))
            putExtra("EXTRA_SETTINGS", json.encodeToString(settings))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(app.title)
            .setLongLabel("${app.title} on ${host.name}")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}
