package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches latest movie rows from vsembed list JSON (no API key).
 */
class VsembedListCatalog(
    private val httpClient: OkHttpClient,
) {
    @Serializable
    data class ListResponse(
        val result: List<ListEntry> = emptyList(),
        val pages: Int = 0,
    )

    @Serializable
    data class ListEntry(
        val imdb_id: String? = null,
        val tmdb_id: String? = null,
        val title: String = "",
        val quality: String? = null,
        val time_added: String? = null,
    )

    @Serializable
    data class EpisodeListEntry(
        val imdb_id: String? = null,
        val tmdb_id: String? = null,
        val show_title: String = "",
        val season: String? = null,
        val episode: String? = null,
        val quality: String? = null,
        val time_added: String? = null,
    )

    data class EpisodeRow(
        val showTmdbId: Int,
        val showImdbId: String?,
        val showTitle: String,
        val season: Int,
        val episode: Int,
        val quality: String?,
    )

    data class MovieRow(
        val tmdbId: Int,
        val imdbId: String?,
        val title: String,
        val quality: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun fetchLatestMovies(pages: Int = VsembedConfig.CATALOG_LIST_PAGES): List<MovieRow> {
        val merged = linkedMapOf<Int, MovieRow>()
        val pageCount = pages.coerceIn(1, 10)
        for (page in 1..pageCount) {
            for (base in VsembedConfig.EMBED_MIRRORS) {
                val url = base + VsembedConfig.LIST_MOVIES_PATH.format(page)
                val rows = runCatching { fetchPage(url) }
                    .getOrElse { exc ->
                        Log.w(TAG, "vsembed list failed: $url", exc)
                        emptyList()
                    }
                if (rows.isNotEmpty()) {
                    rows.forEach { row ->
                        merged.putIfAbsent(row.tmdbId, row)
                    }
                    break
                }
            }
        }
        return merged.values.toList()
    }

    fun fetchLatestEpisodes(pages: Int = VsembedConfig.SERIES_CATALOG_LIST_PAGES): List<EpisodeRow> {
        val merged = linkedMapOf<String, EpisodeRow>()
        val pageCount = pages.coerceIn(1, 10)
        for (page in 1..pageCount) {
            for (base in VsembedConfig.EMBED_MIRRORS) {
                val url = base + VsembedConfig.LIST_EPISODES_PATH.format(page)
                val rows = runCatching { fetchEpisodePage(url) }
                    .getOrElse { exc ->
                        Log.w(TAG, "vsembed episodes list failed: $url", exc)
                        emptyList()
                    }
                if (rows.isNotEmpty()) {
                    rows.forEach { row ->
                        val key = "${row.showTmdbId}:${row.season}:${row.episode}"
                        merged.putIfAbsent(key, row)
                    }
                    break
                }
            }
        }
        return merged.values.toList()
    }

    private fun fetchEpisodePage(url: String): List<EpisodeRow> {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            @Serializable
            data class EpisodeListResponse(
                val result: List<EpisodeListEntry> = emptyList(),
            )
            val parsed = json.decodeFromString<EpisodeListResponse>(body)
            return parsed.result.mapNotNull { entry ->
                val showTmdbId = entry.tmdb_id?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val season = entry.season?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val episode = entry.episode?.trim()?.toIntOrNull() ?: return@mapNotNull null
                if (showTmdbId <= 0 || season <= 0 || episode <= 0) return@mapNotNull null
                val showTitle = entry.show_title.trim().ifBlank { return@mapNotNull null }
                EpisodeRow(
                    showTmdbId = showTmdbId,
                    showImdbId = entry.imdb_id?.trim()?.takeIf { it.startsWith("tt") },
                    showTitle = showTitle,
                    season = season,
                    episode = episode,
                    quality = entry.quality?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private fun fetchPage(url: String): List<MovieRow> {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ListResponse>(body)
            return parsed.result.mapNotNull { entry ->
                val tmdbId = entry.tmdb_id?.trim()?.toIntOrNull() ?: return@mapNotNull null
                if (tmdbId <= 0) return@mapNotNull null
                val title = entry.title.trim().ifBlank { return@mapNotNull null }
                MovieRow(
                    tmdbId = tmdbId,
                    imdbId = entry.imdb_id?.trim()?.takeIf { it.startsWith("tt") },
                    title = title,
                    quality = entry.quality?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    companion object {
        private const val TAG = "VsembedListCatalog"
    }
}
