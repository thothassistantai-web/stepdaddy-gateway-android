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
            val channels = movies.flatMap { movie -> expandMovieChannels(movie) }
            channels to FetchStats(
                fetched = movies.size,
                published = channels.size,
                dedupRemoved = dedupRemoved,
            )
        }

    internal fun expandMovieChannels(movie: TmdbVodCatalog.Movie): List<SupplementChannel> {
        val shelves = resolveMovieShelves(movie)
        if (shelves.size <= 1) {
            val groupTitle = if (shelves.isEmpty()) {
                VodShelfPriority.resolveMovieGroupTitle(emptyList(), movie.genre)
            } else {
                VodShelfPriority.shelfGroupTitle(shelves.first(), isSeries = false)
            }
            return listOf(
                toSupplementChannel(
                    movie = movie,
                    groupTitle = groupTitle,
                    id = TmdbVodConfig.supplementId(movie.tmdbId),
                ),
            )
        }
        return shelves.map { shelf ->
            toSupplementChannel(
                movie = movie,
                groupTitle = VodShelfPriority.shelfGroupTitle(shelf, isSeries = false),
                id = TmdbVodConfig.shelfSupplementId(movie.tmdbId, shelf),
            )
        }
    }

    internal fun resolveMovieShelves(movie: TmdbVodCatalog.Movie): List<String> {
        val shelves = movie.shelfCategories.distinct()
        if (shelves.isNotEmpty()) {
            return VodShelfPriority.sortMovieCategories(shelves)
        }
        if (!movie.genre.isNullOrBlank()) {
            return listOf(movie.genre)
        }
        return emptyList()
    }

    fun toSupplementChannel(
        movie: TmdbVodCatalog.Movie,
        groupTitle: String = VodShelfPriority.resolveMovieGroupTitle(movie.shelfCategories, movie.genre),
        id: String = TmdbVodConfig.supplementId(movie.tmdbId),
    ): SupplementChannel {
        val imdbId = movie.imdbId?.trim()?.takeIf { it.isNotEmpty() }
        val tvgId = when {
            imdbId != null -> imdbId
            else -> "tmdb.${movie.tmdbId}"
        }
        return SupplementChannel(
            id = id,
            name = TmdbVodConfig.movieDisplayTitle(movie.title, movie.releaseDate),
            tvgId = tvgId,
            logo = TmdbVodConfig.normalizePosterUrl(movie.posterUrl),
            groupTitle = groupTitle,
            streamUrl = "",
            tags = listOf("#movies", "#vod"),
            providerTag = TmdbVodConfig.PROVIDER_TAG,
            referer = TmdbVodConfig.EMBED_REFERER,
            plot = movie.overview.takeIf { it.isNotBlank() },
            imdbId = imdbId,
        )
    }

    companion object {
        private const val TAG = "TmdbVodSource"
    }
}
