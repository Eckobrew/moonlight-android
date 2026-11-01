package com.moonlight.material.core.utils

import android.content.Context
import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages caching, disk storage, and retrieval of game box art.
 * Ported from Sources/MoonlightMac/Core/Utils/BoxArtManager.swift.
 */
class BoxArtManager(
    private val context: Context,
    private val httpClient: SunshineHTTPClient? = null
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, "boxart").apply { mkdirs() }
    }

    fun cacheKey(host: Host, app: StreamApp): String {
        val raw = "${host.macAddress ?: host.address}_${app.stableIdentity}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    fun getCachedFile(host: Host, app: StreamApp): File {
        return File(cacheDir, cacheKey(host, app))
    }

    fun isCached(host: Host, app: StreamApp): Boolean {
        val file = getCachedFile(host, app)
        return file.exists() && file.length() > 0
    }

    suspend fun loadBoxArt(host: Host, app: StreamApp, forceRefresh: Boolean = false): File? = withContext(Dispatchers.IO) {
        val cached = getCachedFile(host, app)
        if (!forceRefresh && cached.exists() && cached.length() > 0) {
            return@withContext cached
        }

        try {
            val client = httpClient ?: return@withContext if (cached.exists() && cached.length() > 0) cached else null
            val bytes = client.fetchBoxArt(host, app.id, app.uuid)
            saveToCache(cached, bytes)
            cached
        } catch (e: Exception) {
            AppLogger.warn("Failed to fetch box art for ${app.title}: ${e.message}", "BoxArtManager")
            if (cached.exists() && cached.length() > 0) cached else null
        }
    }

    fun saveToCache(file: File, bytes: ByteArray) {
        try {
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {
            AppLogger.error("Failed to write boxart cache: ${e.message}", "BoxArtManager")
        }
    }

    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            AppLogger.error("Failed to clear boxart cache: ${e.message}", "BoxArtManager")
        }
    }
}
