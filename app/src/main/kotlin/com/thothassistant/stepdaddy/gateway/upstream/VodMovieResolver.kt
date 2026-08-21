package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log

/** vsembed primary, Moviebox SDK fallback; VOD catalog-relay streams preferred when probed OK. */
class VodMovieResolver(
    private val vsembed: VidsrcMovieResolver,
    private val moviebox: MovieboxStreamResolver,
) {
    fun resolveMovie(
        tmdbId: String,
        imdbId: String?,
        title: String?,
    ): VidsrcMovieResolver.ResolvedStream {
        val id = tmdbId.trim().toIntOrNull()
        if (id != null) {
            val relay = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime
                .workingStreamsForMovie(id)
                .firstOrNull()
            if (relay != null) {
                return VidsrcMovieResolver.ResolvedStream(
                    url = relay.url,
                    referer = relay.referer ?: TmdbVodConfig.EMBED_REFERER,
                    isHls = relay.url.contains(".m3u8", ignoreCase = true),
                    provider = "vod-relay",
                )
            }
        }
        runCatching { vsembed.resolveMovie(tmdbId, imdbId) }
            .onSuccess { return it }
            .onFailure { vsembedError ->
                Log.w(TAG, "vsembed failed for tmdb=$tmdbId: ${vsembedError.message}")
                val searchTitle = title?.trim().orEmpty()
                if (searchTitle.isEmpty()) throw vsembedError
                return runCatching { moviebox.resolveMovieByTitle(searchTitle) }
                    .getOrElse { movieboxError ->
                        Log.w(TAG, "moviebox failed for \"$searchTitle\": ${movieboxError.message}")
                        throw vsembedError
                    }
            }
        error("vod_resolve_unreachable")
    }

    fun resolveEpisode(
        showTmdbId: String,
        season: Int,
        episode: Int,
        imdbId: String?,
        showTitle: String?,
    ): VidsrcMovieResolver.ResolvedStream {
        val id = showTmdbId.trim().toIntOrNull()
        if (id != null) {
            val relay = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime
                .workingStreamsForEpisode(id, season, episode)
                .firstOrNull()
            if (relay != null) {
                return VidsrcMovieResolver.ResolvedStream(
                    url = relay.url,
                    referer = relay.referer ?: TmdbVodConfig.EMBED_REFERER,
                    isHls = relay.url.contains(".m3u8", ignoreCase = true),
                    provider = "vod-relay",
                )
            }
        }
        runCatching { vsembed.resolveEpisode(showTmdbId, season, episode, imdbId) }
            .onSuccess { return it }
            .onFailure { vsembedError ->
                Log.w(
                    TAG,
                    "vsembed episode failed tmdb=$showTmdbId S${season}E$episode: ${vsembedError.message}",
                )
                val searchTitle = showTitle?.trim().orEmpty()
                if (searchTitle.isEmpty()) throw vsembedError
                return runCatching {
                    moviebox.resolveEpisodeByTitle(searchTitle, season, episode)
                }.getOrElse { movieboxError ->
                    Log.w(TAG, "moviebox episode failed for \"$searchTitle\": ${movieboxError.message}")
                    throw vsembedError
                }
            }
        error("vod_episode_resolve_unreachable")
    }

    companion object {
        private const val TAG = "VodMovieResolver"
    }
}
