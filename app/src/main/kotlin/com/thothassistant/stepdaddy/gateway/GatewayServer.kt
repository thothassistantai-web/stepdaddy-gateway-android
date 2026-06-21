package com.thothassistant.stepdaddy.gateway

import android.content.Context
import com.thothassistant.stepdaddy.gateway.admin.GatewayAdminActions
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.routes.AdminRoutes
import com.thothassistant.stepdaddy.gateway.routes.ContentRoutes
import com.thothassistant.stepdaddy.gateway.network.createGatewayNetworkPlugin
import com.thothassistant.stepdaddy.gateway.network.GatewayNetworkGuard
import com.thothassistant.stepdaddy.gateway.routes.DlhdEventStreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.EpgRoutes
import com.thothassistant.stepdaddy.gateway.routes.HealthRoutes
import com.thothassistant.stepdaddy.gateway.routes.LogoRoutes
import com.thothassistant.stepdaddy.gateway.routes.NtvStreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.PlaylistRoutes
import com.thothassistant.stepdaddy.gateway.routes.StreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.UiRoutes
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveResolver
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.ResportzParser
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
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
    private val adminActions: GatewayAdminActions? = null,
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
        val dlhdEventStreamRoutes = DlhdEventStreamRoutes(environment, supplementSource)
        val ntvStreamRoutes = NtvStreamRoutes(
            environment,
            supplementSource,
            NtvCxCdnLiveResolver(NtvCxCdnLiveResolver.defaultClient()),
        )
        val contentRoutes = ContentRoutes(environment, client, ResportzParser.defaultClient())
        val epgRoutes = EpgRoutes(client, epgManager)
        val adminRoutes = adminActions?.let { AdminRoutes(it) }

        val bindHost = GatewayNetworkGuard.bindHost(environment.networkAccessMode)
        val gatewayEnvironment = environment
        try {
            engine = embeddedServer(
            CIO,
            host = bindHost,
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
            install(createGatewayNetworkPlugin(gatewayEnvironment))
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
                route("/ntv-stream/{token}.m3u8") {
                    get { ntvStreamRoutes.tivimateStream(call, call.parameters["token"].orEmpty()) }
                    head { ntvStreamRoutes.tivimateStream(call, call.parameters["token"].orEmpty()) }
                }
                route("/dlhd-event-stream/{token}.m3u8") {
                    get { dlhdEventStreamRoutes.eventStream(call, call.parameters["token"].orEmpty()) }
                    head { dlhdEventStreamRoutes.eventStream(call, call.parameters["token"].orEmpty()) }
                }
                route("/dlhd-event-guide/slate.m3u8") {
                    get { dlhdEventStreamRoutes.guideStream(call) }
                    head { dlhdEventStreamRoutes.guideStream(call) }
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
                adminRoutes?.let { admin ->
                    route("/api/v1") {
                        get { admin.discovery(call) }
                        get("/settings") { admin.getSettings(call) }
                        patch("/settings") { admin.patchSettings(call) }
                        get("/channels") { admin.searchChannels(call) }
                        post("/actions/refresh-channels") { admin.refreshChannels(call) }
                        post("/actions/refresh-supplements") { admin.refreshSupplements(call) }
                        post("/actions/refresh-epg") { admin.refreshEpg(call) }
                        post("/actions/refresh-logos") { admin.refreshLogos(call) }
                        post("/actions/refresh-tvg-ids") { admin.refreshTvgIds(call) }
                        post("/actions/prewarm-playlist") { admin.prewarmPlaylist(call) }
                        post("/overrides/logo") { admin.setLogoOverride(call) }
                        delete("/overrides/logo") { admin.clearLogoOverride(call) }
                        post("/overrides/epg-name") { admin.setEpgNameOverride(call) }
                        post("/overrides/epg-id") { admin.setEpgIdOverride(call) }
                        get("/resolve/logo") { admin.resolveLogo(call) }
                        get("/resolve/epg") { admin.resolveEpg(call) }
                        get("/resolve/stream") { admin.resolveStream(call) }
                        get("/channels/{id}") { admin.getChannel(call) }
                        get("/categories/audit") { admin.categoryAudit(call) }
                        post("/actions/stop") { admin.stopGateway(call) }
                        post("/actions/restart") { admin.restartGateway(call) }
                        get("/assets/{type}") { admin.exportAssets(call) }
                        post("/assets/{type}") { admin.importAssets(call) }
                        delete("/assets/{type}") { admin.clearAssets(call) }
                        post("/import/epg-csv") { admin.importEpgCsv(call) }
                        post("/categories/move") { admin.moveCategories(call) }
                        post("/overrides/category") { admin.setCategoryOverride(call) }
                        delete("/overrides/category") { admin.clearCategoryOverride(call) }
                    }
                }
            }
        }.start(wait = false)
            android.util.Log.i(
                "GatewayServer",
                "Listening on $bindHost:${environment.port} mode=${environment.networkAccessMode}",
            )
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
