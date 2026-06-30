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
    )

    suspend fun fetchChannels(forceRefresh: Boolean = false): Pair<List<SupplementChannel>, FetchStats> =
        withContext(Dispatchers.IO) {
            val movies = if (!forceRefresh && !catalogStore.isStale()) {
                catalogStore.read()
            } else {
                val fresh = catalog.fetchCatalog()
                if (fresh.isNotEmpty()) {
                    catalogStore.write(fresh)
                } else {
                    catalogStore.read()
                }
                fresh
            }
            if (movies.isEmpty()) {
                Log.w(TAG, "TMDB VOD catalog empty")
                return@withContext emptyList<SupplementChannel>() to FetchStats()
            }
            val channels = movies.map { movie -> toSupplementChannel(movie) }
            channels to FetchStats(fetched = movies.size, published = channels.size)
        }

    fun toSupplementChannel(movie: TmdbVodCatalog.Movie): SupplementChannel {
        val imdbId = movie.imdbId?.trim()?.takeIf { it.isNotEmpty() }
        val tvgId = when {
            imdbId != null -> imdbId
            else -> "tmdb.${movie.tmdbId}"
        }
        return SupplementChannel(
            id = TmdbVodConfig.supplementId(movie.tmdbId),
            name = TmdbVodConfig.displayTitle(movie.title, movie.releaseDate),
            tvgId = tvgId,
            logo = movie.posterUrl,
            groupTitle = TmdbVodConfig.GROUP_TITLE,
            streamUrl = "",
            tags = listOf("#movies", "#vod"),
            providerTag = TmdbVodConfig.PROVIDER_TAG,
            referer = TmdbVodConfig.VIDSRC_REFERER,
            plot = movie.overview.takeIf { it.isNotBlank() },
            imdbId = imdbId,
        )
    }

    companion object {
        private const val TAG = "TmdbVodSource"
    }
}
