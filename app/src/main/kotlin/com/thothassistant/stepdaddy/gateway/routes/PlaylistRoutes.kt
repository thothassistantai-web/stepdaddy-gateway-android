package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.ChannelMetaStore
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistBuilder
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val logoResolver: LogoResolver,
    private val channelMetaStore: ChannelMetaStore,
    private val supplementSource: SupplementSource,
    private val playlistCache: PlaylistCache,
) {
    suspend fun tivimatePlaylist(call: ApplicationCall) {
        try {
            val body = buildPlaylistBody()
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
            call.response.header(HttpHeaders.Pragma, "no-cache")
            call.respondText(body, ContentType("application", "vnd.apple.mpegurl"))
        } catch (exc: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (exc.message ?: "playlist_unavailable")),
            )
        }
    }

    fun schedulePrewarm() {
        val channels = client.channels
        val supplements = supplementSource.channels()
        val cacheKey = playlistCache.computeKey(
            channelCount = channels.size,
            supplementCount = supplements.size,
            supplementSyncedAtMs = supplementSource.lastSyncedAtMs(),
            channelRevision = client.channelRevision(),
            logoDbLoaded = logoResolver.isLoaded(),
            playlistTitleStyle = environment.playlistTitleStyle,
        )
        playlistCache.schedulePrewarm(cacheKey) {
            buildPlaylistBodySync(channels, supplements)
        }
    }

    private suspend fun buildPlaylistBody(): String = withContext(Dispatchers.IO) {
        val channels = client.channels
        val supplements = supplementSource.channels()
        val cacheKey = playlistCache.computeKey(
            channelCount = channels.size,
            supplementCount = supplements.size,
            supplementSyncedAtMs = supplementSource.lastSyncedAtMs(),
            channelRevision = client.channelRevision(),
            logoDbLoaded = logoResolver.isLoaded(),
            playlistTitleStyle = environment.playlistTitleStyle,
        )
        playlistCache.getOrBuild(cacheKey) {
            buildPlaylistBodySync(channels, supplements)
        }
    }

    private fun buildPlaylistBodySync(
        channels: List<com.thothassistant.stepdaddy.gateway.model.Channel>,
        supplements: List<com.thothassistant.stepdaddy.gateway.model.SupplementChannel>,
    ): String {
        if (channels.isEmpty()) {
            return PlaylistBuilder.minimalPlaylist(environment.loopbackBase())
        }
        return PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = environment.loopbackBase(),
            dlhdOrigin = client.activeBaseUrl,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = environment.playlistTitleStyle,
        )
    }
}
