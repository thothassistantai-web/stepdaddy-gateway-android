package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TmdbVodCatalogStore(context: Context) {
    private val file = File(context.filesDir, "supplement/tmdb_vod.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Cache(
        val movies: List<TmdbVodCatalog.Movie> = emptyList(),
        val syncedAtMs: Long = 0L,
    )

    fun read(): List<TmdbVodCatalog.Movie> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<Cache>(file.readText()).movies
        }.getOrDefault(emptyList())
    }

    fun write(movies: List<TmdbVodCatalog.Movie>) {
        file.parentFile?.mkdirs()
        val payload = Cache(movies = movies, syncedAtMs = System.currentTimeMillis())
        file.writeText(json.encodeToString(payload))
    }

    fun isStale(): Boolean {
        if (!file.exists()) return true
        val syncedAt = runCatching {
            json.decodeFromString<Cache>(file.readText()).syncedAtMs
        }.getOrDefault(0L)
        return System.currentTimeMillis() - syncedAt > TmdbVodConfig.CACHE_STALE_MS
    }
}
