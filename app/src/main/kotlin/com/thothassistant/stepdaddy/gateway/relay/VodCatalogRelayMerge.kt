package com.thothassistant.stepdaddy.gateway.relay

import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodCatalog
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodSeriesCatalog
import com.thothassistant.stepdaddy.gateway.upstream.VodMovieDedup

/**
 * Merge + dedup helpers for VOD catalog relay overlays.
 */
object VodCatalogRelayMerge {
    fun dedupeManifest(manifest: VodCatalogRelayManifest): VodCatalogRelayManifest {
        val movies = linkedMapOf<String, VodRelayMovie>()
        for (movie in manifest.movies) {
            val key = VodCatalogRelayValidator.movieIdentityKey(movie)
            val existing = movies[key]
            movies[key] = if (existing == null) {
                movie
            } else {
                mergeMovies(existing, movie)
            }
        }
        val shows = linkedMapOf<String, VodRelayShow>()
        for (show in manifest.shows) {
            val key = VodCatalogRelayValidator.showIdentityKey(show)
            val existing = shows[key]
            shows[key] = if (existing == null) {
                show
            } else {
                mergeShows(existing, show)
            }
        }
        return manifest.copy(
            movies = movies.values.toList(),
            shows = shows.values.toList(),
        )
    }

    fun toCatalogMovies(overlay: List<VodRelayMovie>): List<TmdbVodCatalog.Movie> =
        overlay.filter { it.tmdbId > 0 }.map { movie ->
            TmdbVodCatalog.Movie(
                tmdbId = movie.tmdbId,
                title = movie.title.ifBlank { "Movie ${movie.tmdbId}" },
                overview = movie.overview,
                releaseDate = movie.year,
                posterUrl = movie.posterUrl,
                imdbId = movie.imdbId,
                streamQuality = movie.streams.firstOrNull()?.quality,
                shelfCategories = listOf("Relay Finds"),
            )
        }

    fun toCatalogEpisodes(overlay: List<VodRelayShow>): List<TmdbVodSeriesCatalog.Episode> =
        overlay.filter { it.tmdbId > 0 && it.season > 0 && it.episode > 0 }.map { show ->
            TmdbVodSeriesCatalog.Episode(
                showTmdbId = show.tmdbId,
                showTitle = show.title.ifBlank { "Show ${show.tmdbId}" },
                season = show.season,
                episode = show.episode,
                showImdbId = show.imdbId,
                overview = show.overview.ifBlank { show.episodeTitle.orEmpty() },
                posterUrl = show.posterUrl,
                streamQuality = show.streams.firstOrNull()?.quality,
                showYear = show.year,
                shelfCategories = listOf("Relay Finds"),
            )
        }

    /**
     * Merge overlay movies into an existing catalog list, then run [VodMovieDedup].
     * Overlay streams / quality win when identity matches.
     */
    fun mergeMoviesIntoCatalog(
        existing: List<TmdbVodCatalog.Movie>,
        overlay: List<VodRelayMovie>,
    ): VodMovieDedup.Result {
        val overlayMovies = toCatalogMovies(overlay)
        return VodMovieDedup.dedupe(existing + overlayMovies)
    }

    fun mergeEpisodesIntoCatalog(
        existing: List<TmdbVodSeriesCatalog.Episode>,
        overlay: List<VodRelayShow>,
    ): List<TmdbVodSeriesCatalog.Episode> {
        val byKey = linkedMapOf<String, TmdbVodSeriesCatalog.Episode>()
        for (ep in existing) {
            byKey["${ep.showTmdbId}:${ep.season}:${ep.episode}"] = ep
        }
        for (ep in toCatalogEpisodes(overlay)) {
            val key = "${ep.showTmdbId}:${ep.season}:${ep.episode}"
            val prev = byKey[key]
            byKey[key] = if (prev == null) {
                ep
            } else {
                prev.copy(
                    showTitle = prev.showTitle.ifBlank { ep.showTitle },
                    overview = prev.overview.ifBlank { ep.overview },
                    posterUrl = prev.posterUrl ?: ep.posterUrl,
                    showImdbId = prev.showImdbId ?: ep.showImdbId,
                    streamQuality = ep.streamQuality ?: prev.streamQuality,
                    shelfCategories = VodMovieDedup.mergeShelfCategories(
                        prev.shelfCategories,
                        ep.shelfCategories,
                    ),
                )
            }
        }
        return byKey.values.toList()
    }

    private fun mergeMovies(a: VodRelayMovie, b: VodRelayMovie): VodRelayMovie {
        val preferB = b.streams.size > a.streams.size ||
            (b.overview.length > a.overview.length) ||
            (b.posterUrl != null && a.posterUrl == null)
        val primary = if (preferB) b else a
        val secondary = if (preferB) a else b
        return primary.copy(
            title = primary.title.ifBlank { secondary.title },
            year = primary.year ?: secondary.year,
            imdbId = primary.imdbId ?: secondary.imdbId,
            overview = primary.overview.ifBlank { secondary.overview },
            posterUrl = primary.posterUrl ?: secondary.posterUrl,
            streams = (primary.streams + secondary.streams).distinctBy { it.url },
            tmdbId = maxOf(primary.tmdbId, secondary.tmdbId).takeIf { it > 0 }
                ?: primary.tmdbId.takeIf { it > 0 }
                ?: secondary.tmdbId,
        )
    }

    private fun mergeShows(a: VodRelayShow, b: VodRelayShow): VodRelayShow {
        val preferB = b.streams.size > a.streams.size
        val primary = if (preferB) b else a
        val secondary = if (preferB) a else b
        return primary.copy(
            title = primary.title.ifBlank { secondary.title },
            year = primary.year ?: secondary.year,
            imdbId = primary.imdbId ?: secondary.imdbId,
            overview = primary.overview.ifBlank { secondary.overview },
            posterUrl = primary.posterUrl ?: secondary.posterUrl,
            episodeTitle = primary.episodeTitle ?: secondary.episodeTitle,
            streams = (primary.streams + secondary.streams).distinctBy { it.url },
            tmdbId = maxOf(primary.tmdbId, secondary.tmdbId).takeIf { it > 0 }
                ?: primary.tmdbId.takeIf { it > 0 }
                ?: secondary.tmdbId,
        )
    }
}
