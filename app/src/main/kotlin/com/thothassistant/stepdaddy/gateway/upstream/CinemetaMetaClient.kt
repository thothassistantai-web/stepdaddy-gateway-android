package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Free per-title metadata from Stremio Cinemeta (no API key). */
class CinemetaMetaClient(
    private val httpClient: OkHttpClient,
) {
    @Serializable
    data class MetaResponse(
        val meta: Meta? = null,
    )

    @Serializable
    data class Meta(
        val name: String = "",
        val description: String = "",
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("moviedb_id") val moviedbId: Int? = null,
        val year: String? = null,
        val poster: String? = null,
        @SerialName("imdbRating") val imdbRating: String? = null,
        val released: String? = null,
        val genre: List<String>? = null,
        val cast: List<String>? = null,
    )

    data class EnrichedMeta(
        val title: String,
        val overview: String,
        val releaseDate: String?,
        val voteAverage: Double,
        val posterUrl: String?,
        val genre: String? = null,
        val cast: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun fetchMovieMeta(imdbId: String): EnrichedMeta? =
        fetchMeta("movie", imdbId)

    fun fetchSeriesMeta(imdbId: String): EnrichedMeta? =
        fetchMeta("series", imdbId)

    private fun fetchMeta(type: String, imdbId: String): EnrichedMeta? {
        val normalized = imdbId.trim()
        if (!normalized.startsWith("tt")) return null
        val url = "https://v3-cinemeta.strem.io/meta/$type/$normalized.json"
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val meta = json.decodeFromString<MetaResponse>(body).meta ?: return@use null
                if (meta.name.isBlank()) return@use null
                EnrichedMeta(
                    title = meta.name,
                    overview = meta.description,
                    releaseDate = meta.year ?: meta.released?.take(4),
                    voteAverage = meta.imdbRating?.toDoubleOrNull() ?: 0.0,
                    posterUrl = TmdbVodConfig.normalizePosterUrl(meta.poster)
                        ?: meta.imdbId?.let { TmdbVodConfig.metahubPosterUrl(it) },
                    genre = meta.genre?.joinToString(" / ")?.takeIf { it.isNotBlank() },
                    cast = meta.cast?.take(6)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { exc ->
            Log.w(TAG, "Cinemeta meta failed: $imdbId", exc)
            null
        }
    }

    companion object {
        private const val TAG = "CinemetaMetaClient"
    }
}
