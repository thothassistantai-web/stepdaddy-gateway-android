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
            val channels = episodes.flatMap { episode -> expandEpisodeChannels(episode) }
            channels to FetchStats(fetched = episodes.size, published = channels.size)
        }

    internal fun expandEpisodeChannels(episode: TmdbVodSeriesCatalog.Episode): List<SupplementChannel> {
        val shelves = resolveEpisodeShelves(episode)
        if (shelves.size <= 1) {
            val groupTitle = if (shelves.isEmpty()) {
                VodShelfPriority.resolveSeriesGroupTitle(
                    shelfCategories = emptyList(),
                    genre = episode.genre,
                    showTitle = episode.showTitle,
                    showShelf = episode.showShelf,
                )
            } else {
                VodShelfPriority.shelfGroupTitle(shelves.first(), isSeries = true)
            }
            return listOf(
                toSupplementChannel(
                    episode = episode,
                    groupTitle = groupTitle,
                    id = TmdbVodConfig.seriesSupplementId(episode.showTmdbId, episode.season, episode.episode),
                ),
            )
        }
        val baseId = TmdbVodConfig.seriesSupplementId(episode.showTmdbId, episode.season, episode.episode)
        return shelves.map { shelf ->
            toSupplementChannel(
                episode = episode,
                groupTitle = VodShelfPriority.shelfGroupTitle(shelf, isSeries = true),
                id = TmdbVodConfig.shelfSeriesSupplementId(baseId, shelf),
            )
        }
    }

    internal fun resolveEpisodeShelves(episode: TmdbVodSeriesCatalog.Episode): List<String> {
        val shelves = episode.shelfCategories.distinct()
        if (shelves.isNotEmpty()) {
            return VodShelfPriority.sortSeriesCategories(shelves)
        }
        if (!episode.genre.isNullOrBlank() &&
            NextboxConfig.TV_SECTIONS.any { episode.genre.equals(it, ignoreCase = true) }
        ) {
            return listOf(episode.genre)
        }
        if (episode.showShelf) {
            return listOf(episode.showTitle)
        }
        if (!episode.genre.isNullOrBlank()) {
            return listOf(episode.genre)
        }
        return emptyList()
    }

    fun toSupplementChannel(
        episode: TmdbVodSeriesCatalog.Episode,
        groupTitle: String = VodShelfPriority.resolveSeriesGroupTitle(
            episode.shelfCategories,
            episode.genre,
            episode.showTitle,
            episode.showShelf,
        ),
        id: String = TmdbVodConfig.seriesSupplementId(episode.showTmdbId, episode.season, episode.episode),
    ): SupplementChannel {
        val imdbId = episode.showImdbId?.trim()?.takeIf { it.isNotEmpty() }
        val tvgId = imdbId ?: "tmdb.${episode.showTmdbId}"
        return SupplementChannel(
            id = id,
            name = TmdbVodConfig.episodeDisplayTitle(episode.showTitle, episode.season, episode.episode),
            tvgId = tvgId,
            logo = TmdbVodConfig.normalizePosterUrl(episode.posterUrl),
            groupTitle = groupTitle,
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
