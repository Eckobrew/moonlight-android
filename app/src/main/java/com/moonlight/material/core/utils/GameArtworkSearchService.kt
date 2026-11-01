package com.moonlight.material.core.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ArtworkSearchResult(
    val title: String,
    val imageUrl: String,
    val source: String
)

/**
 * Searches public artwork providers (Steam Storefront / SteamGridDB) for fallback game covers.
 * Ported from Sources/MoonlightMac/Core/Utils/GameArtworkSearchService.swift.
 */
class GameArtworkSearchService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "ArtworkSearchService"

    suspend fun searchArtwork(gameTitle: String): List<ArtworkSearchResult> = withContext(Dispatchers.IO) {
        val sanitized = gameTitle.trim()
        if (sanitized.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<ArtworkSearchResult>()

        try {
            // Search Steam Storefront public API
            val encodedQuery = URLEncoder.encode(sanitized, "UTF-8")
            val url = "https://store.steampowered.com/api/storesearch/?term=$encodedQuery&l=english&cc=US"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MoonlightAndroid")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until minOf(items.length(), 5)) {
                            val item = items.getJSONObject(i)
                            val id = item.optInt("id")
                            val name = item.optString("name")
                            val tinyImage = item.optString("tiny_image")
                            if (id > 0) {
                                // High quality Steam library capsule and header
                                val libraryCapsule = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900_2x.jpg"
                                val headerImage = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/header.jpg"
                                results.add(ArtworkSearchResult(name, libraryCapsule, "Steam Library Capsule"))
                                results.add(ArtworkSearchResult(name, headerImage, "Steam Header"))
                            } else if (tinyImage.isNotEmpty()) {
                                results.add(ArtworkSearchResult(name, tinyImage, "Steam Search"))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.warn("Steam artwork search error: ${e.message}", TAG)
        }

        results
    }
}
