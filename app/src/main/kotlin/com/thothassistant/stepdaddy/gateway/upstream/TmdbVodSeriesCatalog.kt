package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

/** Latest TV episodes from vsembed list JSON + Cinemeta show metadata (Xtream-style titles/posters). */
class TmdbVodSeriesCatalog(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val vsembedList: VsembedListCatalog = VsembedListCatalog(httpClient),
    private val cinemetaMeta: CinemetaMetaClient = CinemetaMetaClient(httpClient),
    private val nextboxCatalog: NextboxCatalog = NextboxCatalog(httpClient),
) {
    @Serializable
    data class Episode(
        val showTmdbId: Int,
        val showTitle: String,
        val season: Int,
        val episode: Int,
        val showImdbId: String? = null,
        val overview: String = "",
        val posterUrl: String? = null,
        val streamQuality: String? = null,
        val showYear: String? = null,
        /** Nextbox / vsembed shelf labels — separate from Cinemeta genre metadata. */
        val shelfCategories: List<String> = emptyList(),
        /** Cinemeta metadata genre only. */
        val genre: String? = null,
        val cast: String? = null,
        val showShelf: Boolean = false,
    )

    fun fetchCatalog(): List<Episode> {
        val vsembedPages = VodCatalogLimits.vsembedSeriesPages(context)
        val rows = vsembedList.fetchLatestEpisodes(pages = vsembedPages)
        if (rows.isEmpty()) {
            Log.w(TAG, "vsembed episodes list empty")
            return emptyList()
        }
        val nextboxShows = runCatching { nextboxCatalog.fetchShows() }
            .getOrElse { exc ->
                Log.w(TAG, "nextbox show scrape failed", exc)
                emptyList()
            }
        val nextboxCategories = nextboxShows.associate { it.showTmdbId to it.categories }
        val showShelfIds = vsembedList.fetchLatestTvShows(pages = vsembedPages)
            .map { it.showTmdbId }
            .toSet()
            .plus(nextboxCategories.keys)
        val showMetaCache = mutableMapOf<String, CinemetaMetaClient.EnrichedMeta>()
        val episodes = rows.map { row ->
            val nextboxCats = nextboxCategories[row.showTmdbId].orEmpty()
            val shelves = buildList {
                add("Latest Shows")
                addAll(nextboxCats)
            }.distinct()
            toEpisode(
                row,
                showMetaCache,
                showShelf = showShelfIds.contains(row.showTmdbId),
                shelfCategories = shelves,
            )
        }
        val cap = VodCatalogLimits.seriesCap(context)
        return VodShelfPriority.capEpisodes(episodes, cap)
    }

    private fun toEpisode(
        row: VsembedListCatalog.EpisodeRow,
        showMetaCache: MutableMap<String, CinemetaMetaClient.EnrichedMeta>,
        showShelf: Boolean,
        shelfCategories: List<String> = emptyList(),
    ): Episode {
        val parsed = TmdbVodConfig.parseListTitle(row.showTitle)
        val imdbId = row.showImdbId
        val meta = if (imdbId != null) {
            showMetaCache.getOrPut(imdbId) {
                cinemetaMeta.fetchSeriesMeta(imdbId)
                    ?: CinemetaMetaClient.EnrichedMeta(
                        title = parsed.title,
                        overview = "",
                        releaseDate = parsed.year,
                        voteAverage = 0.0,
                        posterUrl = TmdbVodConfig.metahubPosterUrl(imdbId),
                    )
            }
        } else {
            null
        }
        val showTitle = meta?.title?.takeIf { it.isNotBlank() } ?: parsed.title
        val posterUrl = meta?.posterUrl
            ?: imdbId?.let { TmdbVodConfig.metahubPosterUrl(it) }
        val overview = buildPlot(meta)
        return Episode(
            showTmdbId = row.showTmdbId,
            showTitle = showTitle,
            season = row.season,
            episode = row.episode,
            showImdbId = imdbId,
            overview = overview,
            posterUrl = posterUrl,
            streamQuality = row.quality,
            showYear = meta?.releaseDate ?: parsed.year,
            shelfCategories = shelfCategories,
            genre = meta?.genre,
            cast = meta?.cast,
            showShelf = showShelf,
        )
    }

    private fun buildPlot(meta: CinemetaMetaClient.EnrichedMeta?): String {
        if (meta == null) return ""
        val parts = mutableListOf<String>()
        meta.overview.takeIf { it.isNotBlank() }?.let { parts += it }
        meta.genre?.takeIf { it.isNotBlank() }?.let { parts += "Genre: $it" }
        meta.cast?.takeIf { it.isNotBlank() }?.let { parts += "Cast: $it" }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val TAG = "TmdbVodSeriesCatalog"
    }
}
