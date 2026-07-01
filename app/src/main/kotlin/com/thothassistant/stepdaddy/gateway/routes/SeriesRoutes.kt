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

class SeriesRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
) {
    @Serializable
    data class EpisodeSummary(
        val showTmdbId: Int,
        val season: Int,
        val episode: Int,
        val title: String,
        val plot: String? = null,
        val poster: String? = null,
        val imdbId: String? = null,
        val streamUrl: String,
    )

    @Serializable
    data class SeriesResponse(
        val enabled: Boolean,
        val count: Int,
        val episodes: List<EpisodeSummary>,
    )

    suspend fun list(call: ApplicationCall) {
        val enabled = environment.supplementTmdbMoviesEnabled
        if (!enabled) {
            call.respond(
                HttpStatusCode.OK,
                SeriesResponse(enabled = false, count = 0, episodes = emptyList()),
            )
            return
        }
        val base = environment.loopbackBase().trimEnd('/')
        val episodes = supplementSource.channels()
            .filter { it.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) }
            .mapNotNull { channel ->
                val key = TmdbVodConfig.parseSeriesSupplementId(channel.id) ?: return@mapNotNull null
                EpisodeSummary(
                    showTmdbId = key.showTmdbId,
                    season = key.season,
                    episode = key.episode,
                    title = channel.name,
                    plot = channel.plot,
                    poster = channel.logo,
                    imdbId = channel.imdbId,
                    streamUrl = "$base/vod/series/${key.showTmdbId}/${key.season}/${key.episode}.m3u8",
                )
            }
        call.respond(SeriesResponse(enabled = true, count = episodes.size, episodes = episodes))
    }

    suspend fun disabled(call: ApplicationCall) {
        call.respondText("Series VOD catalog disabled", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}
