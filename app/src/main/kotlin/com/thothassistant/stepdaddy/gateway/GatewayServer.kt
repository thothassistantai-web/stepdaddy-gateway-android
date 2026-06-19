package com.thothassistant.stepdaddy.gateway

import android.content.Context
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.routes.ContentRoutes
import com.thothassistant.stepdaddy.gateway.routes.EpgRoutes
import com.thothassistant.stepdaddy.gateway.routes.HealthRoutes
import com.thothassistant.stepdaddy.gateway.routes.LogoRoutes
import com.thothassistant.stepdaddy.gateway.routes.PlaylistRoutes
import com.thothassistant.stepdaddy.gateway.routes.StreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.UiRoutes
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.ResportzParser
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
    private val logoResolver: com.thothassistant.stepdaddy.gateway.upstream.LogoResolver,
    private val channelMetaStore: com.thothassistant.stepdaddy.gateway.upstream.ChannelMetaStore,
    private val supplementSource: com.thothassistant.stepdaddy.gateway.upstream.SupplementSource,
    private val playlistCache: PlaylistCache,
) {
    private val uiRoutes = UiRoutes(context.applicationContext, logoResolver)
    private var playlistRoutes: PlaylistRoutes? = null
    private val fallbackSvg: ByteArray =
        context.assets.open("ui/default-channel.svg").use { it.readBytes() }
    private val logoRoutes = LogoRoutes(File(context.filesDir, "logo-cache"), fallbackSvg)
    @Volatile
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) return
        val healthRoutes = HealthRoutes(environment, client, epgManager, supplementSource)
        val routes = PlaylistRoutes(
            environment,
            client,
            logoResolver,
            channelMetaStore,
            supplementSource,
            playlistCache,
        )
        playlistRoutes = routes
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
                    get { routes.tivimatePlaylist(call) }
                    head { routes.tivimatePlaylist(call) }
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
                get("/ui/channel/{token}.svg") {
                    uiRoutes.channelPlaceholder(call, call.parameters["token"].orEmpty())
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

    fun prewarmPlaylist() {
        playlistRoutes?.schedulePrewarm()
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        playlistRoutes = null
    }
}
