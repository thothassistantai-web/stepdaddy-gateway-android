package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable

class MoviesRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
) {
    @Serializable
    data class MovieSummary(
        val tmdbId: Int,
        val title: String,
        val plot: String? = null,
        val poster: String? = null,
        val imdbId: String? = null,
        val streamUrl: String,
    )

    @Serializable
    data class MoviesResponse(
        val enabled: Boolean,
        val count: Int,
        val movies: List<MovieSummary>,
    )

    suspend fun list(call: ApplicationCall) {
        val enabled = environment.supplementTmdbMoviesEnabled
        if (!enabled) {
            call.respond(
                HttpStatusCode.OK,
                MoviesResponse(enabled = false, count = 0, movies = emptyList()),
            )
            return
        }
        val base = environment.loopbackBase().trimEnd('/')
        val movies = supplementSource.channels()
            .filter { it.id.startsWith(TmdbVodConfig.ID_PREFIX) }
            .mapNotNull { channel ->
                val tmdbId = TmdbVodConfig.tmdbIdFromSupplementId(channel.id)?.toIntOrNull() ?: return@mapNotNull null
                MovieSummary(
                    tmdbId = tmdbId,
                    title = channel.name,
                    plot = channel.plot,
                    poster = channel.logo,
                    imdbId = channel.imdbId,
                    streamUrl = "$base/vod/movie/$tmdbId.m3u8",
                )
            }
        call.respond(MoviesResponse(enabled = true, count = movies.size, movies = movies))
    }

    suspend fun disabled(call: ApplicationCall) {
        call.respondText("TMDB movies catalog disabled", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}
