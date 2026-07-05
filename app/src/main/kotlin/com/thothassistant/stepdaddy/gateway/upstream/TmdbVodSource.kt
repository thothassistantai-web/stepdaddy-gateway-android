package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds supplement VOD rows from TMDB trending / popular movie lists.
 */
class TmdbVodSource(
    private val catalog: TmdbVodCatalog,
    private val catalogStore: TmdbVodCatalogStore,
) {
    data class FetchStats(
        val fetched: Int = 0,
        val published: Int = 0,
        val dedupRemoved: Int = 0,
    )

    suspend fun fetchChannels(forceRefresh: Boolean = false): Pair<List<SupplementChannel>, FetchStats> =
        withContext(Dispatchers.IO) {
            val (movies, dedupRemoved) = if (!forceRefresh && !catalogStore.isStale()) {
                catalogStore.read() to 0
            } else {
                val result = catalog.fetchCatalog()
                val fresh = result.movies
                if (fresh.isNotEmpty()) {
                    catalogStore.write(fresh)
                    fresh to result.dedupRemoved
                } else {
                    catalogStore.read() to 0
                }
            }
            if (movies.isEmpty()) {
                Log.w(TAG, "TMDB VOD catalog empty")
                return@withContext emptyList<SupplementChannel>() to FetchStats()
            }
            val channels = movies.map { movie -> toSupplementChannel(movie) }
            channels to FetchStats(
                fetched = movies.size,
                published = channels.size,
                dedupRemoved = dedupRemoved,
            )
        }

    fun toSupplementChannel(movie: TmdbVodCatalog.Movie): SupplementChannel {
        val imdbId = movie.imdbId?.trim()?.takeIf { it.isNotEmpty() }
        val tvgId = when {
            imdbId != null -> imdbId
            else -> "tmdb.${movie.tmdbId}"
        }
        return SupplementChannel(
            id = TmdbVodConfig.supplementId(movie.tmdbId),
            name = TmdbVodConfig.movieDisplayTitle(movie.title, movie.releaseDate),
            tvgId = tvgId,
            logo = TmdbVodConfig.normalizePosterUrl(movie.posterUrl),
            groupTitle = movieGroupTitle(movie.genre),
            streamUrl = "",
            tags = listOf("#movies", "#vod"),
            providerTag = TmdbVodConfig.PROVIDER_TAG,
            referer = TmdbVodConfig.EMBED_REFERER,
            plot = movie.overview.takeIf { it.isNotBlank() },
            imdbId = imdbId,
        )
    }

    internal fun movieGroupTitle(genre: String?): String {
        if (genre.isNullOrBlank()) return VodCategoryResolver.movieGroupTitle(null)
        val sections = genre.split("/", "·", "|", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val nextboxSection = sections.firstOrNull { section ->
            NextboxConfig.MOVIE_SECTIONS.any { it.equals(section, ignoreCase = true) }
        }
        if (nextboxSection != null) {
            return VodCategoryResolver.nextboxMovieGroupTitle(nextboxSection)
        }
        return VodCategoryResolver.movieGroupTitle(genre)
    }

    companion object {
        private const val TAG = "TmdbVodSource"
    }
}
