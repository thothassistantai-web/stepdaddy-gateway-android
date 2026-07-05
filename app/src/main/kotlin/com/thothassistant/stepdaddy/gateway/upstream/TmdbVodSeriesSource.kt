package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds supplement VOD rows for latest TV episodes. */
class TmdbVodSeriesSource(
    private val catalog: TmdbVodSeriesCatalog,
    private val catalogStore: TmdbVodSeriesCatalogStore,
) {
    data class FetchStats(
        val fetched: Int = 0,
        val published: Int = 0,
    )

    suspend fun fetchChannels(forceRefresh: Boolean = false): Pair<List<SupplementChannel>, FetchStats> =
        withContext(Dispatchers.IO) {
            val episodes = if (!forceRefresh && !catalogStore.isStale()) {
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
            if (episodes.isEmpty()) {
                Log.w(TAG, "series VOD catalog empty")
                return@withContext emptyList<SupplementChannel>() to FetchStats()
            }
            val channels = episodes.map { episode -> toSupplementChannel(episode) }
            channels to FetchStats(fetched = episodes.size, published = channels.size)
        }

    fun toSupplementChannel(episode: TmdbVodSeriesCatalog.Episode): SupplementChannel {
        val imdbId = episode.showImdbId?.trim()?.takeIf { it.isNotEmpty() }
        val tvgId = imdbId ?: "tmdb.${episode.showTmdbId}"
        return SupplementChannel(
            id = TmdbVodConfig.seriesSupplementId(episode.showTmdbId, episode.season, episode.episode),
            name = TmdbVodConfig.episodeDisplayTitle(episode.showTitle, episode.season, episode.episode),
            tvgId = tvgId,
            logo = TmdbVodConfig.normalizePosterUrl(episode.posterUrl),
            groupTitle = when {
                !episode.genre.isNullOrBlank() &&
                    NextboxConfig.TV_SECTIONS.any { episode.genre.equals(it, ignoreCase = true) } ->
                    VodCategoryResolver.nextboxSeriesGroupTitle(episode.genre)
                else -> VodCategoryResolver.seriesGroupTitle(
                    genre = episode.genre,
                    showTitle = episode.showTitle,
                    showShelf = episode.showShelf,
                )
            },
            streamUrl = "",
            tags = listOf("#series", "#vod", "#shows"),
            providerTag = TmdbVodConfig.PROVIDER_TAG,
            referer = TmdbVodConfig.EMBED_REFERER,
            plot = episode.overview.takeIf { it.isNotBlank() },
            imdbId = imdbId,
        )
    }

    companion object {
        private const val TAG = "TmdbVodSeriesSource"
    }
}
