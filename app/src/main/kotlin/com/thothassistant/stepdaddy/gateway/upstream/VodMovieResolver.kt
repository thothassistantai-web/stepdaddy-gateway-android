package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log

/** vsembed primary, Moviebox SDK fallback. */
class VodMovieResolver(
    private val vsembed: VidsrcMovieResolver,
    private val moviebox: MovieboxStreamResolver,
) {
    fun resolveMovie(
        tmdbId: String,
        imdbId: String?,
        title: String?,
    ): VidsrcMovieResolver.ResolvedStream {
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
