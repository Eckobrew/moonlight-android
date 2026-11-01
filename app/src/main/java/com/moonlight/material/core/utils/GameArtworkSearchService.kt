package com.moonlight.material.core.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class ArtworkCategory(val label: String) { ALL("All"), COVERS("Covers"), LOGOS("Logos & Icons"), BANNERS("Banners") }
data class ArtworkSearchResult(val title: String, val imageUrl: String, val source: String, val category: ArtworkCategory = ArtworkCategory.COVERS)

class GameArtworkSearchService(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()) {
    suspend fun searchArtwork(gameTitle: String, steamGridDBKey: String = ""): List<ArtworkSearchResult> = withContext(Dispatchers.IO) {
        if (gameTitle.isBlank()) return@withContext emptyList()
        val query = URLEncoder.encode(gameTitle.trim(), "UTF-8")
        val results = mutableListOf<ArtworkSearchResult>()
        var failure: Exception? = null
        fun get(url: String, key: String? = null): JSONObject {
            val builder = Request.Builder().url(url)
            if (key != null) builder.header("Authorization", "Bearer $key")
            return client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artwork provider returned HTTP ${response.code}.")
                JSONObject(response.body?.string() ?: throw IOException("Artwork provider returned an empty response."))
            }
        }
        try {
            val items = get("https://store.steampowered.com/api/storesearch/?term=$query&l=english&cc=US").optJSONArray("items")
            if (items != null) for (i in 0 until minOf(items.length(), 5)) {
                val item = items.getJSONObject(i); val id = item.optInt("id"); val name = item.optString("name")
                if (id <= 0) continue
                val base = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id"
                results += ArtworkSearchResult(name, "$base/library_600x900_2x.jpg", "Steam Cover", ArtworkCategory.COVERS)
                results += ArtworkSearchResult(name, "$base/logo.png", "Steam Logo", ArtworkCategory.LOGOS)
                results += ArtworkSearchResult(name, "$base/header.jpg", "Steam Header", ArtworkCategory.BANNERS)
                results += ArtworkSearchResult(name, "$base/library_hero.jpg", "Steam Hero", ArtworkCategory.BANNERS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e }
        if (steamGridDBKey.isNotBlank()) {
            try {
                val games = get("https://www.steamgriddb.com/api/v2/search/autocomplete/$query", steamGridDBKey).optJSONArray("data")
                if (games != null && games.length() > 0) {
                    val game = games.getJSONObject(0); val id = game.getInt("id"); val name = game.getString("name")
                    for ((kind, category) in listOf("icons" to ArtworkCategory.LOGOS, "grids" to ArtworkCategory.COVERS)) {
                        val items = get("https://www.steamgriddb.com/api/v2/$kind/game/$id", steamGridDBKey).optJSONArray("data") ?: continue
                        for (i in 0 until minOf(items.length(), 12)) {
                            val url = items.getJSONObject(i).optString("url")
                            if (url.startsWith("https://")) results += ArtworkSearchResult(name, url, "SteamGridDB", category)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (results.isEmpty()) throw e
                AppLogger.warn("SteamGridDB artwork unavailable: ${e.message}", "ARTWORK")
            }
        }
        if (results.isEmpty() && failure != null) throw failure
        results.distinctBy { it.imageUrl }
    }
}
