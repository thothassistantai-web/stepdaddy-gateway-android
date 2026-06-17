package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.ChannelMetaStore
import com.nova.stepdaddylivehd.gateway.upstream.LogoResolver
import com.nova.stepdaddylivehd.gateway.upstream.PlaylistBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

class PlaylistRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val logoResolver: LogoResolver,
    private val channelMetaStore: ChannelMetaStore,
) {
    suspend fun tivimatePlaylist(call: ApplicationCall) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val cached = client.channels
            client.scheduleChannelRefresh()
            val body = if (cached.isNotEmpty()) {
                PlaylistBuilder.tivimatePlaylist(
                    channels = cached,
                    baseUrl = environment.loopbackBase(),
                    dlhdOrigin = client.activeBaseUrl,
                    logoResolver = logoResolver,
                    channelMetaStore = channelMetaStore,
                )
            } else {
                PlaylistBuilder.minimalPlaylist(environment.loopbackBase())
            }
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
}
