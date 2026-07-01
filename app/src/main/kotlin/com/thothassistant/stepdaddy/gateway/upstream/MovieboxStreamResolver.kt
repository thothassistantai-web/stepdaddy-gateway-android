package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Moviebox stream resolver — search by title, play via /subject/play API.
 * Ported from pythonvista/moviebox-js-sdk.
 */
class MovieboxStreamResolver(
    private val session: MovieboxSession,
) {
    @Serializable
    private data class SearchRequest(
        val keyword: String,
        val page: Int = 1,
        val perPage: Int = 12,
        @SerialName("subjectType") val subjectType: Int = 1,
    )

    @Serializable
    private data class ApiEnvelope<T>(
        val code: Int = -1,
        val message: String = "",
        val data: T? = null,
    )

    @Serializable
    private data class SearchData(
        val items: List<SearchItem> = emptyList(),
    )

    @Serializable
    private data class SearchItem(
        val subjectId: String = "",
        val title: String = "",
        val detailPath: String = "",
        @SerialName("subjectType") val subjectType: Int = 0,
    )

    @Serializable
    private data class StreamData(
        val streams: List<StreamFile> = emptyList(),
    )

    @Serializable
    private data class StreamFile(
        val url: String = "",
        val resolutions: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun resolveMovieByTitle(title: String): VidsrcMovieResolver.ResolvedStream {
        val query = title.trim()
        if (query.isEmpty()) error("moviebox_empty_title")
        val searchBody = json.encodeToString(SearchRequest(keyword = query))
        val searchRaw = session.postJson(MovieboxConfig.SEARCH_PATH, searchBody)
        val searchEnvelope = json.decodeFromString<ApiEnvelope<SearchData>>(searchRaw)
        val item = searchEnvelope.data?.items
            ?.firstOrNull { it.subjectType == 1 && it.subjectId.isNotBlank() }
            ?: error("moviebox_search_miss")
        val subjectId = item.subjectId
        val slug = item.detailPath.substringAfterLast('/').ifBlank { item.detailPath }
        val referer = session.buildUrl("/movies/$slug")
        val streamRaw = session.getJson(
            path = MovieboxConfig.STREAM_PATH,
            query = mapOf(
                "subjectId" to subjectId,
                "se" to "0",
                "ep" to "0",
            ),
            referer = referer,
        )
        val streamEnvelope = json.decodeFromString<ApiEnvelope<StreamData>>(streamRaw)
        val streams = streamEnvelope.data?.streams.orEmpty()
        val best = streams.maxByOrNull { it.resolutions.toIntOrNull() ?: 0 }
            ?: error("moviebox_no_streams")
        val url = best.url.trim()
        if (url.isEmpty()) error("moviebox_empty_stream")
        Log.i(TAG, "moviebox stream resolved for \"$query\" (${best.resolutions}p)")
        return VidsrcMovieResolver.ResolvedStream(
            url = url,
            referer = referer,
            isHls = url.contains(".m3u8", ignoreCase = true),
            provider = "moviebox",
        )
    }

    fun resolveEpisodeByTitle(
        showTitle: String,
        season: Int,
        episode: Int,
    ): VidsrcMovieResolver.ResolvedStream {
        val query = showTitle.trim()
        if (query.isEmpty()) error("moviebox_empty_title")
        val searchBody = json.encodeToString(SearchRequest(keyword = query, subjectType = 2))
        val searchRaw = session.postJson(MovieboxConfig.SEARCH_PATH, searchBody)
        val searchEnvelope = json.decodeFromString<ApiEnvelope<SearchData>>(searchRaw)
        val item = searchEnvelope.data?.items
            ?.firstOrNull { it.subjectType == 2 && it.subjectId.isNotBlank() }
            ?: error("moviebox_series_search_miss")
        val subjectId = item.subjectId
        val slug = item.detailPath.substringAfterLast('/').ifBlank { item.detailPath }
        val referer = session.buildUrl("/detail/$slug")
        val streamRaw = session.getJson(
            path = MovieboxConfig.STREAM_PATH,
            query = mapOf(
                "subjectId" to subjectId,
                "se" to season.toString(),
                "ep" to episode.toString(),
            ),
            referer = referer,
        )
        val streamEnvelope = json.decodeFromString<ApiEnvelope<StreamData>>(streamRaw)
        val streams = streamEnvelope.data?.streams.orEmpty()
        val best = streams.maxByOrNull { it.resolutions.toIntOrNull() ?: 0 }
            ?: error("moviebox_no_streams")
        val url = best.url.trim()
        if (url.isEmpty()) error("moviebox_empty_stream")
        Log.i(TAG, "moviebox episode resolved for \"$query\" S${season}E$episode")
        return VidsrcMovieResolver.ResolvedStream(
            url = url,
            referer = referer,
            isHls = url.contains(".m3u8", ignoreCase = true),
            provider = "moviebox",
        )
    }

    companion object {
        private const val TAG = "MovieboxStreamResolver"
    }
}
