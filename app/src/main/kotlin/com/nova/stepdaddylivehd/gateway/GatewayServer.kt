package com.nova.stepdaddylivehd.gateway

import android.content.Context
import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.routes.ContentRoutes
import com.nova.stepdaddylivehd.gateway.routes.EpgRoutes
import com.nova.stepdaddylivehd.gateway.routes.HealthRoutes
import com.nova.stepdaddylivehd.gateway.routes.LogoRoutes
import com.nova.stepdaddylivehd.gateway.routes.PlaylistRoutes
import com.nova.stepdaddylivehd.gateway.routes.StreamRoutes
import com.nova.stepdaddylivehd.gateway.routes.UiRoutes
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.ResportzParser
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
import java.io.File
import kotlinx.serialization.json.Json

class GatewayServer(
    context: Context,
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val logoResolver: com.nova.stepdaddylivehd.gateway.upstream.LogoResolver,
) {
    private val uiRoutes = UiRoutes(context.applicationContext)
    private val logoRoutes = LogoRoutes(File(context.filesDir, "logo-cache"))
    @Volatile
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) return
        val healthRoutes = HealthRoutes(environment, client, epgManager)
        val playlistRoutes = PlaylistRoutes(environment, client, logoResolver)
        val streamRoutes = StreamRoutes(environment, client)
        val contentRoutes = ContentRoutes(environment, client, ResportzParser.defaultClient())
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
                get("/content/{path}") {
                    contentRoutes.content(call, call.parameters["path"].orEmpty())
                }
                get("/key/{url}/{host}") {
                    contentRoutes.key(
                        call,
                        call.parameters["url"].orEmpty(),
                        call.parameters["host"].orEmpty(),
                    )
                }
                route("/epg.xml") {
                    get { epgRoutes.epgXml(call) }
                    head { epgRoutes.epgXml(call) }
                }
                get("/logo/{token}") {
                    logoRoutes.logo(call, call.parameters["token"].orEmpty())
                }
                get("/ui/default-channel.svg") {
                    uiRoutes.defaultChannelLogo(call)
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
