package com.nova.stepdaddylivehd.gateway

import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.routes.EpgRoutes
import com.nova.stepdaddylivehd.gateway.routes.HealthRoutes
import com.nova.stepdaddylivehd.gateway.routes.PlaylistRoutes
import com.nova.stepdaddylivehd.gateway.routes.StreamRoutes
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

class GatewayServer(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
) {
    @Volatile
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) return
        val healthRoutes = HealthRoutes(environment, client, epgManager)
        val playlistRoutes = PlaylistRoutes(environment, client)
        val streamRoutes = StreamRoutes(client)
        val epgRoutes = EpgRoutes(client, epgManager)

        try {
            engine = embeddedServer(
            CIO,
            host = "0.0.0.0",
            port = environment.port,
            configure = {
                // Keep client connections open through slow resportz fetches (Python allows 60s+).
                connectionIdleTimeoutSeconds = 300
                reuseAddress = true
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/health") {
                    healthRoutes.health(call)
                }
                get("/tivimate-setup") {
                    healthRoutes.tivimateSetup(call)
                }
                route("/tivimate-playlist.m3u8") {
                    get { playlistRoutes.tivimatePlaylist(call) }
                    head { playlistRoutes.tivimatePlaylist(call) }
                }
                route("/tivimate-stream/{channelId}.m3u8") {
                    get { streamRoutes.tivimateStream(call, call.parameters["channelId"].orEmpty()) }
                    head { streamRoutes.tivimateStream(call, call.parameters["channelId"].orEmpty()) }
                }
                route("/stream/{channelId}.m3u8") {
                    get { streamRoutes.genericStream(call, call.parameters["channelId"].orEmpty()) }
                    head { streamRoutes.genericStream(call, call.parameters["channelId"].orEmpty()) }
                }
                route("/epg.xml") {
                    get { epgRoutes.epgXml(call) }
                    head { epgRoutes.epgXml(call) }
                }
            }
        }.start(wait = false)
            android.util.Log.i("GatewayServer", "Listening on 0.0.0.0:${environment.port}")
        } catch (exc: Exception) {
            engine = null
            android.util.Log.e("GatewayServer", "Failed to bind port ${environment.port}", exc)
            throw exc
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }
}
