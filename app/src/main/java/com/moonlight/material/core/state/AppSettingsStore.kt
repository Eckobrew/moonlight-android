package com.moonlight.material.core.state

import android.content.Context
import com.moonlight.material.core.models.AppStreamOverrides
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("moonlight_settings_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadSettings(): StreamSettings {
        val raw = prefs.getString("global_settings", null) ?: return StreamSettings()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            StreamSettings()
        }
    }

    fun saveSettings(settings: StreamSettings) {
        val raw = json.encodeToString(settings)
        prefs.edit().putString("global_settings", raw).apply()
    }

    fun loadHosts(): List<Host> {
        val raw = prefs.getString("saved_hosts", null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHosts(hosts: List<Host>) {
        val raw = json.encodeToString(hosts)
        prefs.edit().putString("saved_hosts", raw).apply()
    }

    fun loadAppOverrides(hostId: String, appId: Int): AppStreamOverrides {
        val key = "override_${hostId}_$appId"
        val raw = prefs.getString(key, null) ?: return AppStreamOverrides()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            AppStreamOverrides()
        }
    }

    fun saveAppOverrides(hostId: String, appId: Int, overrides: AppStreamOverrides) {
        val key = "override_${hostId}_$appId"
        val raw = json.encodeToString(overrides)
        prefs.edit().putString(key, raw).apply()
    }
}
