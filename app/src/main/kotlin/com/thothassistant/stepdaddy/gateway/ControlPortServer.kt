package com.thothassistant.stepdaddy.gateway

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.model.TiviMateChannelRow
import com.thothassistant.stepdaddy.gateway.model.TiviMateChannelsPayload
import com.thothassistant.stepdaddy.gateway.model.TiviMateHttpStatus
import com.thothassistant.stepdaddy.gateway.model.TiviMatePlayerState
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ControlPortServer(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private var engine: ApplicationEngine? = null

    fun start() {
        engine?.stop(1_000, 5_000)
        engine = embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = TiviMateController.HTTP_CONTROL_PORT,
        ) {
            install(ContentNegotiation) {
                json(json)
            }
            routing {
                get("/") { call.respond(statusPayload()) }
                get("/status") { call.respond(statusPayload()) }
                get("/state") { call.respond(statePayload()) }
                get("/channels") { call.respond(channelsPayload(call.request.queryParameters["limit"])) }
                get("/boot-tune/{channel}") { handleBootTune(call) }
                get("/tune/{channel}") { handleParamAction(call, "tuned") }
                get("/stream/{channel}") { handleParamAction(call, "stream_opened") }
                get("/channel/up") { call.respond(actionResponse("key_sent")) }
                get("/channel/down") { call.respond(actionResponse("key_sent")) }
                get("/pause") { call.respond(actionResponse("key_sent")) }
                get("/play") { call.respond(actionResponse("key_sent")) }
                get("/search") { handleSearch(call) }
                get("/setup") { call.respond(actionResponse("setup_started")) }
                get("/launch") { call.respond(actionResponse("launched")) }
                get("/epg") { call.respond(actionResponse("epg_opened")) }
                get("/ui") { call.respond(actionResponse("ui")) }
            }
        }.apply { start(false) }
    }

    fun stop() {
        engine?.stop(1_000, 5_000)
        engine = null
    }

    private fun statusPayload(): TiviMateHttpStatus {
        val channels = client.channels
        val playerState = statePayload()
        return TiviMateHttpStatus(
            ok = true,
            patchVersion = "GatewayStock",
            packageName = "com.thothassistant.stepdaddy.gateway",
            setupDone = true,
            hasPlaylist = true,
            playlistCount = channels.size,
            channelCount = channels.size,
            wizardPending = false,
            playlistName = "TiViMate Gateway",
            playlistUrl = "${environment.loopbackBase()}/tivimate-playlist.m3u8",
            gatewayBase = environment.loopbackBase(),
            port = environment.port,
            wizardPhase = playerState.wizardPhase,
            stateReason = playerState.stateReason,
        )
    }

    private fun statePayload(): TiviMatePlayerState {
        val currentChannel = client.channels.firstOrNull()
        return TiviMatePlayerState(
            ok = true,
            setupDone = true,
            wizardPending = false,
            stateReason = "stock-gateway",
            playlistName = "TiViMate Gateway",
            playlistUrl = "${environment.loopbackBase()}/tivimate-playlist.m3u8",
            currentChannelName = currentChannel?.name,
            currentChannelNo = currentChannel?.let { client.channels.indexOf(it) + 1 },
            channelCount = client.channels.size,
            gatewayBase = environment.loopbackBase(),
            patchVersion = "GatewayStock",
        )
    }

    private fun channelsPayload(limitParam: String?): TiviMateChannelsPayload {
        val limit = limitParam?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val rows = client.channels.take(limit).mapIndexed { index, channel ->
            TiviMateChannelRow(
                id = channel.id.toLongOrNull() ?: 0L,
                channelNo = index + 1,
                name = channel.name,
            )
        }
        return TiviMateChannelsPayload(ok = true, channels = rows)
    }

    private suspend fun handleBootTune(call: ApplicationCall) {
        val channel = call.parameters["channel"]?.toIntOrNull()?.takeIf { it > 0 }
        if (channel == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                actionResponse("invalid_channel", ok = false),
            )
            return
        }
        environment.tivimateBootTuneChannel = channel
        call.respond(actionResponse("boot_tune_saved", channel))
    }

    private suspend fun handleParamAction(call: ApplicationCall, status: String) {
        val channel = call.parameters["channel"]?.toIntOrNull()?.takeIf { it > 0 }
        if (channel == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                actionResponse("invalid_channel", ok = false),
            )
            return
        }
        call.respond(actionResponse(status, channel))
    }

    private suspend fun handleSearch(call: ApplicationCall) {
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        val status = if (query.isBlank()) "search_failed" else "search_opened"
        call.respond(actionResponse(status))
    }

    private fun actionResponse(status: String, channel: Int? = null, ok: Boolean = true) =
        ControlActionResponse(ok = ok, status = status, channel = channel)

    @Serializable
    private data class ControlActionResponse(
        val ok: Boolean = true,
        val status: String,
        val channel: Int? = null,
    )
}
