package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Builds the VOD movie catalog: vsembed latest list (primary), Cinemeta enrichment,
 * optional TMDB API when a key is configured.
 */
class TmdbVodCatalog(
    private val httpClient: OkHttpClient,
    private val apiKey: () -> String,
    private val vsembedList: VsembedListCatalog = VsembedListCatalog(httpClient),
    private val cinemetaMeta: CinemetaMetaClient = CinemetaMetaClient(httpClient),
    private val nextboxCatalog: NextboxCatalog = NextboxCatalog(httpClient),
) {
    @Serializable
    data class Movie(
        val tmdbId: Int,
        val title: String,
        val overview: String = "",
        val releaseDate: String? = null,
        val voteAverage: Double = 0.0,
        val posterUrl: String? = null,
        val imdbId: String? = null,
        val streamQuality: String? = null,
        val genre: String? = null,
    )

    @Serializable
    private data class MovieListResponse(
        val results: List<MovieJson> = emptyList(),
    )

    @Serializable
    private data class MovieJson(
        val id: Int,
        val title: String,
        val overview: String = "",
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("vote_average") val voteAverage: Double = 0.0,
        @SerialName("poster_path") val posterPath: String? = null,
    )

    @Serializable
    private data class CinemetaCatalog(
        val metas: List<CinemetaMeta> = emptyList(),
    )

    @Serializable
    private data class CinemetaMeta(
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("moviedb_id") val moviedbId: Int? = null,
        val name: String = "",
        val description: String = "",
        val year: String? = null,
        val poster: String? = null,
        @SerialName("imdbRating") val imdbRating: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    data class CatalogResult(
        val movies: List<Movie>,
        val dedupRemoved: Int = 0,
    )

    fun fetchCatalog(): CatalogResult {
        val merged = linkedMapOf<Int, Movie>()

        vsembedList.fetchLatestMovies().forEach { row ->
            val parsed = TmdbVodConfig.parseListTitle(row.title)
            merged[row.tmdbId] = Movie(
                tmdbId = row.tmdbId,
                title = parsed.title,
                releaseDate = parsed.year,
                imdbId = row.imdbId,
                streamQuality = row.quality,
                posterUrl = row.imdbId?.let { TmdbVodConfig.metahubPosterUrl(it) },
            )
        }

        runCatching { nextboxCatalog.fetchMovies() }
            .getOrElse { exc ->
                Log.w(TAG, "nextbox movie scrape failed", exc)
                emptyList()
            }
            .forEach { row ->
                val existing = merged[row.tmdbId]
                merged[row.tmdbId] = Movie(
                    tmdbId = row.tmdbId,
                    title = row.title.ifBlank { existing?.title.orEmpty() },
                    releaseDate = row.year ?: existing?.releaseDate,
                    imdbId = existing?.imdbId,
                    streamQuality = existing?.streamQuality,
                    posterUrl = existing?.posterUrl,
                    genre = row.category,
                    overview = existing?.overview.orEmpty(),
                    voteAverage = existing?.voteAverage ?: 0.0,
                )
            }

        if (merged.isEmpty()) {
            Log.w(TAG, "vsembed list empty — falling back to Cinemeta catalogs")
            fetchCinemetaCatalog().forEach { movie ->
                merged.putIfAbsent(movie.tmdbId, movie)
            }
        }

        enrichWithCinemetaMeta(merged)

        val key = apiKey().trim()
        if (key.isNotEmpty()) {
            listOf(
                tmdbUrl("trending/movie/week", key, page = 1),
                tmdbUrl("movie/popular", key, page = 1),
            ).forEach { url ->
                runCatching { fetchTmdbPage(url) }
                    .getOrElse { exc ->
                        Log.w(TAG, "TMDB fetch failed: $url", exc)
                        emptyList()
                    }
                    .forEach { movie ->
                        val existing = merged[movie.tmdbId]
                        merged[movie.tmdbId] = if (existing == null) {
                            movie
                        } else {
                            existing.copy(
                                overview = movie.overview.ifBlank { existing.overview },
                                posterUrl = movie.posterUrl ?: existing.posterUrl,
                                voteAverage = movie.voteAverage.takeIf { it > 0.0 } ?: existing.voteAverage,
                                title = existing.title.ifBlank { movie.title },
                            )
                        }
                    }
            }
        }

        val beforeDedup = merged.values.toList()
        val deduped = VodMovieDedup.dedupe(beforeDedup)
        if (deduped.removedCount > 0) {
            Log.i(
                TAG,
                "VOD movie dedup: removed ${deduped.removedCount} duplicate movies " +
                    "(${deduped.inputCount} -> ${deduped.outputCount})",
            )
        }

        return CatalogResult(
            movies = deduped.movies
                .sortedWith(
                    compareByDescending<TmdbVodCatalog.Movie> {
                        VodSort.movieSortKey(it.releaseDate, it.title)
                    },
                )
                .take(TmdbVodConfig.MAX_CATALOG_SIZE),
            dedupRemoved = deduped.removedCount,
        )
    }

    private fun enrichWithCinemetaMeta(merged: LinkedHashMap<Int, Movie>) {
        var enriched = 0
        for ((tmdbId, movie) in merged) {
            if (enriched >= TmdbVodConfig.MAX_CINEMETA_ENRICH) break
            val imdbId = movie.imdbId ?: continue
            val meta = cinemetaMeta.fetchMovieMeta(imdbId) ?: continue
            enriched++
            merged[tmdbId] = movie.copy(
                title = meta.title.ifBlank { movie.title },
                overview = meta.overview.ifBlank { movie.overview },
                releaseDate = meta.releaseDate ?: movie.releaseDate,
                voteAverage = meta.voteAverage.takeIf { it > 0.0 } ?: movie.voteAverage,
                posterUrl = meta.posterUrl ?: movie.posterUrl,
                genre = meta.genre ?: movie.genre,
            )
        }
    }

    private fun fetchCinemetaCatalog(): List<Movie> {
        val endpoints = listOf(
            "https://v3-cinemeta.strem.io/catalog/movie/top.json",
            "https://v3-cinemeta.strem.io/catalog/movie/imdbRating.json",
        )
        val merged = linkedMapOf<Int, Movie>()
        for (endpoint in endpoints) {
            runCatching {
                val request = Request.Builder().url(endpoint).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString<CinemetaCatalog>(body).metas
                }
            }.getOrElse { exc ->
                Log.w(TAG, "Cinemeta fetch failed: $endpoint", exc)
                emptyList()
            }.forEach { meta ->
                val tmdbId = meta.moviedbId ?: return@forEach
                if (tmdbId <= 0 || meta.name.isBlank()) return@forEach
                val rating = meta.imdbRating?.toDoubleOrNull() ?: 0.0
                merged.putIfAbsent(
                    tmdbId,
                    Movie(
                        tmdbId = tmdbId,
                        title = meta.name,
                        overview = meta.description,
                        releaseDate = meta.year,
                        voteAverage = rating,
                        posterUrl = TmdbVodConfig.normalizePosterUrl(meta.poster)
                            ?: meta.imdbId?.let { TmdbVodConfig.metahubPosterUrl(it) },
                        imdbId = meta.imdbId?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
        return merged.values.toList()
    }

    private fun fetchTmdbPage(url: String): List<Movie> {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<MovieListResponse>(body)
            return parsed.results.map { row ->
                Movie(
                    tmdbId = row.id,
                    title = row.title,
                    overview = row.overview,
                    releaseDate = row.releaseDate,
                    voteAverage = row.voteAverage,
                    posterUrl = TmdbVodConfig.posterUrl(row.posterPath),
                )
            }
        }
    }

    private fun tmdbUrl(path: String, apiKey: String, page: Int? = null): String {
        val base = TmdbVodConfig.TMDB_API_BASE.toHttpUrl()
        val builder = base.newBuilder()
            .addPathSegments(path.trimStart('/'))
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", "en-US")
        if (page != null) {
            builder.addQueryParameter("page", page.toString())
        }
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "TmdbVodCatalog"
    }
}
