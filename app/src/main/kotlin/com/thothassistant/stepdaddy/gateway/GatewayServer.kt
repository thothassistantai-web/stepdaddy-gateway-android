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
import com.thothassistant.stepdaddy.gateway.routes.MoviesRoutes
import com.thothassistant.stepdaddy.gateway.routes.SeriesRoutes
import com.thothassistant.stepdaddy.gateway.routes.XtreamApiRoutes
import com.thothassistant.stepdaddy.gateway.routes.NtvStreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.XyzStreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.VodStreamRoutes
import com.thothassistant.stepdaddy.gateway.upstream.MovieboxSession
import com.thothassistant.stepdaddy.gateway.upstream.MovieboxStreamResolver
import com.thothassistant.stepdaddy.gateway.upstream.VidsrcMovieResolver
import com.thothassistant.stepdaddy.gateway.upstream.VodMovieResolver
import com.thothassistant.stepdaddy.gateway.upstream.VodStreamCache
import com.thothassistant.stepdaddy.gateway.routes.PlaylistPaths
import com.thothassistant.stepdaddy.gateway.routes.PlaylistRoutes
import com.thothassistant.stepdaddy.gateway.routes.SupplementFallbackStreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.StreamRoutes
import com.thothassistant.stepdaddy.gateway.routes.TiviMateRoutes
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
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
    private val appContext = context.applicationContext
    private val uiRoutes = UiRoutes(appContext, logoResolver)
    private var playlistRoutes: PlaylistRoutes? = null
    private val fallbackSvg: ByteArray =
        appContext.assets.open("ui/default-channel.svg").use { it.readBytes() }
    private val logoRoutes = LogoRoutes(File(appContext.filesDir, "logo-cache"), fallbackSvg)
    @Volatile
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) return
        val healthRoutes = HealthRoutes(
            appContext,
            environment,
            client,
            epgManager,
            supplementSource,
            playlistCache,
        )
        val tiviMateRoutes = TiviMateRoutes(appContext, environment)
        val routes = PlaylistRoutes(
            environment,
            client,
            logoResolver,
            channelMetaStore,
            supplementSource,
            playlistCache,
            supplementSource.dlhdEventHealthStore(),
        )
        playlistRoutes = routes
        val fireLite = FireTvDevice.isFireTv(appContext)
        val streamRoutes = StreamRoutes(environment, client)
        val dlhdEventStreamRoutes = DlhdEventStreamRoutes(appContext, environment, supplementSource, client)
        val ntvHttp = if (fireLite) {
            FireMemoryGuard.compactHttpClient()
        } else {
            NtvCxCdnLiveResolver.defaultClient()
        }
        val ntvResolver = NtvCxCdnLiveResolver(ntvHttp)
        val supplementFallbackRoutes = SupplementFallbackStreamRoutes(
            environment,
            supplementSource,
            client,
            ntvResolver,
        )
        val ntvStreamRoutes = NtvStreamRoutes(
            environment,
            supplementSource,
            ntvResolver,
        )
        val xyzStreamRoutes = XyzStreamRoutes(environment, supplementSource)
        val vodStreamCache = VodStreamCache()
        val vodHttpClient = if (fireLite) {
            FireMemoryGuard.compactHttpClient()
        } else {
            VidsrcMovieResolver.defaultClient()
        }
        val vodMovieResolver = VodMovieResolver(
            VidsrcMovieResolver(vodHttpClient),
            MovieboxStreamResolver(MovieboxSession(vodHttpClient)),
        )
        val vodStreamRoutes = VodStreamRoutes(
            environment,
            supplementSource,
            vodMovieResolver,
            vodStreamCache,
            vodHttpClient,
        )
        val moviesRoutes = MoviesRoutes(environment, supplementSource)
        val seriesRoutes = SeriesRoutes(environment, supplementSource)
        val xtreamApiRoutes = XtreamApiRoutes(environment, client, supplementSource)
        val contentHttp = if (fireLite) {
            FireMemoryGuard.compactHttpClient()
        } else {
            ResportzParser.defaultClient()
        }
        val contentRoutes = ContentRoutes(environment, client, contentHttp)
        val epgRoutes = EpgRoutes(client, epgManager, supplementSource)
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
                // Fire Stick: shorter idle + fewer workers to cut native/thread RAM.
                connectionIdleTimeoutSeconds = if (fireLite) 60 else 300
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
                get("/streamvault-setup") {
                    healthRoutes.streamvaultSetup(call)
                }
                post("/tivimate-events") {
                    tiviMateRoutes.postEvent(call)
                }
                get("/tivimate-events") {
                    tiviMateRoutes.getEvents(call)
                }
                get("/tivimate-handshake") {
                    tiviMateRoutes.handshake(call)
                }
                get("/tivimate-state") {
                    tiviMateRoutes.state(call)
                }
                route(PlaylistPaths.TIVIMATE) {
                    get { routes.tivimateUserPlaylist(call) }
                    head { routes.tivimateUserPlaylist(call) }
                }
                route(PlaylistPaths.TIVIMATE_M3U8) {
                    get { routes.tivimateUserPlaylist(call) }
                    head { routes.tivimateUserPlaylist(call) }
                }
                route(PlaylistPaths.STREAMVAULT) {
                    get { routes.streamVaultUserPlaylist(call) }
                    head { routes.streamVaultUserPlaylist(call) }
                }
                route(PlaylistPaths.STREAMVAULT_M3U8) {
                    get { routes.streamVaultUserPlaylist(call) }
                    head { routes.streamVaultUserPlaylist(call) }
                }
                route(PlaylistPaths.VLC) {
                    get { routes.vlcUserPlaylist(call) }
                    head { routes.vlcUserPlaylist(call) }
                }
                route(PlaylistPaths.VLC_M3U8) {
                    get { routes.vlcUserPlaylist(call) }
                    head { routes.vlcUserPlaylist(call) }
                }
                route(PlaylistPaths.TIVIMATE_LEGACY) {
                    get { routes.tivimateUserPlaylist(call) }
                    head { routes.tivimateUserPlaylist(call) }
                }
                route(PlaylistPaths.TIVIMATE_SETUP) {
                    get { routes.tivimateSetupPlaylist(call) }
                    head { routes.tivimateSetupPlaylist(call) }
                }
                route(PlaylistPaths.STREAMVAULT_SETUP) {
                    get { routes.streamVaultSetupPlaylist(call) }
                    head { routes.streamVaultSetupPlaylist(call) }
                }
                route(PlaylistPaths.STREAMVAULT_LEGACY) {
                    get { routes.streamVaultUserPlaylist(call) }
                    head { routes.streamVaultUserPlaylist(call) }
                }
                route("/tivimate-stream/{channelId}.m3u8") {
                    get {
                        val channelId = call.parameters["channelId"].orEmpty()
                        if (channelId.startsWith("dlhd-event-")) {
                            val token = channelId.removePrefix("dlhd-event-")
                            dlhdEventStreamRoutes.eventStreamMaster(call, token)
                        } else if (supplementSource.daddyChannelFallbacks(channelId).isNotEmpty()) {
                            supplementFallbackRoutes.daddyMaster(call, channelId)
                        } else {
                            streamRoutes.tivimateStream(call, channelId)
                        }
                    }
                    head {
                        val channelId = call.parameters["channelId"].orEmpty()
                        if (channelId.startsWith("dlhd-event-")) {
                            val token = channelId.removePrefix("dlhd-event-")
                            dlhdEventStreamRoutes.eventStreamMaster(call, token)
                        } else if (supplementSource.daddyChannelFallbacks(channelId).isNotEmpty()) {
                            supplementFallbackRoutes.daddyMaster(call, channelId)
                        } else {
                            streamRoutes.tivimateStream(call, channelId)
                        }
                    }
                }
                route("/daddy-fallback/{channelId}/{index}.m3u8") {
                    get {
                        val channelId = call.parameters["channelId"].orEmpty()
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        supplementFallbackRoutes.daddyMirror(call, channelId, index)
                    }
                    head {
                        val channelId = call.parameters["channelId"].orEmpty()
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        supplementFallbackRoutes.daddyMirror(call, channelId, index)
                    }
                }
                route("/supplement-stream/{supplementId}/master.m3u8") {
                    get {
                        val id = SupplementFallbackStreamRoutes.decodeId(call.parameters["supplementId"].orEmpty())
                        supplementFallbackRoutes.supplementMaster(call, id)
                    }
                    head {
                        val id = SupplementFallbackStreamRoutes.decodeId(call.parameters["supplementId"].orEmpty())
                        supplementFallbackRoutes.supplementMaster(call, id)
                    }
                }
                route("/supplement-stream/{supplementId}/{index}.m3u8") {
                    get {
                        val id = SupplementFallbackStreamRoutes.decodeId(call.parameters["supplementId"].orEmpty())
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        supplementFallbackRoutes.supplementMirror(call, id, index)
                    }
                    head {
                        val id = SupplementFallbackStreamRoutes.decodeId(call.parameters["supplementId"].orEmpty())
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        supplementFallbackRoutes.supplementMirror(call, id, index)
                    }
                }
                route("/dlhd-event-mirror/{token}/{index}.m3u8") {
                    get {
                        val token = call.parameters["token"].orEmpty()
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        dlhdEventStreamRoutes.eventMirrorStream(call, token, index)
                    }
                    head {
                        val token = call.parameters["token"].orEmpty()
                        val index = call.parameters["index"]?.toIntOrNull() ?: 0
                        dlhdEventStreamRoutes.eventMirrorStream(call, token, index)
                    }
                }
                route("/ntv-stream/{token}.m3u8") {
                    get { ntvStreamRoutes.tivimateStream(call, call.parameters["token"].orEmpty()) }
                    head { ntvStreamRoutes.tivimateStream(call, call.parameters["token"].orEmpty()) }
                }
                route("/xyz-stream/{streamId}.m3u8") {
                    get { xyzStreamRoutes.tivimateStream(call, call.parameters["streamId"].orEmpty()) }
                    head { xyzStreamRoutes.tivimateStream(call, call.parameters["streamId"].orEmpty()) }
                }
                route("/vod/movie/{tmdbId}.m3u8") {
                    get { vodStreamRoutes.movieStream(call, call.parameters["tmdbId"].orEmpty()) }
                    head { vodStreamRoutes.movieStream(call, call.parameters["tmdbId"].orEmpty()) }
                }
                route("/vod/movie/{tmdbId}.mp4") {
                    get { vodStreamRoutes.movieStream(call, call.parameters["tmdbId"].orEmpty()) }
                    head { vodStreamRoutes.movieStream(call, call.parameters["tmdbId"].orEmpty()) }
                }
                route("/vod/series/{tmdbId}/{season}/{episode}.m3u8") {
                    get {
                        vodStreamRoutes.seriesStream(
                            call,
                            call.parameters["tmdbId"].orEmpty(),
                            call.parameters["season"].orEmpty(),
                            call.parameters["episode"].orEmpty(),
                        )
                    }
                    head {
                        vodStreamRoutes.seriesStream(
                            call,
                            call.parameters["tmdbId"].orEmpty(),
                            call.parameters["season"].orEmpty(),
                            call.parameters["episode"].orEmpty(),
                        )
                    }
                }
                route("/vod/series/{tmdbId}/{season}/{episode}.mp4") {
                    get {
                        vodStreamRoutes.seriesStream(
                            call,
                            call.parameters["tmdbId"].orEmpty(),
                            call.parameters["season"].orEmpty(),
                            call.parameters["episode"].orEmpty(),
                        )
                    }
                    head {
                        vodStreamRoutes.seriesStream(
                            call,
                            call.parameters["tmdbId"].orEmpty(),
                            call.parameters["season"].orEmpty(),
                            call.parameters["episode"].orEmpty(),
                        )
                    }
                }
                get("/movies") {
                    if (gatewayEnvironment.supplementTmdbMoviesEnabled) {
                        moviesRoutes.list(call)
                    } else {
                        moviesRoutes.disabled(call)
                    }
                }
                get("/series") {
                    if (gatewayEnvironment.supplementTmdbMoviesEnabled) {
                        seriesRoutes.list(call)
                    } else {
                        seriesRoutes.disabled(call)
                    }
                }
                get("/player_api.php") {
                    xtreamApiRoutes.playerApi(call)
                }
                get("/get.php") {
                    routes.xtreamGetPhp(call)
                }
                head("/get.php") {
                    routes.xtreamGetPhp(call)
                }
                route("/live/{user}/{pass}/{streamId}.{ext}") {
                    get {
                        val user = call.parameters["user"].orEmpty()
                        val pass = call.parameters["pass"].orEmpty()
                        if (!gatewayEnvironment.isXtreamAuthorized(user, pass)) {
                            call.respondText("Authentication failed", status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        val streamId = call.parameters["streamId"].orEmpty()
                        call.respondRedirect("/tivimate-stream/$streamId.m3u8", permanent = false)
                    }
                    head {
                        val user = call.parameters["user"].orEmpty()
                        val pass = call.parameters["pass"].orEmpty()
                        if (!gatewayEnvironment.isXtreamAuthorized(user, pass)) {
                            call.respondText("", status = HttpStatusCode.Unauthorized)
                            return@head
                        }
                    }
                }
                route("/movie/{user}/{pass}/{streamId}.{ext}") {
                    get {
                        val user = call.parameters["user"].orEmpty()
                        val pass = call.parameters["pass"].orEmpty()
                        if (!gatewayEnvironment.isXtreamAuthorized(user, pass)) {
                            call.respondText("Authentication failed", status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        val id = call.parameters["streamId"].orEmpty()
                        call.respondRedirect("/vod/movie/$id.m3u8", permanent = false)
                    }
                }
                route("/series/{user}/{pass}/{streamId}.{ext}") {
                    get {
                        val user = call.parameters["user"].orEmpty()
                        val pass = call.parameters["pass"].orEmpty()
                        if (!gatewayEnvironment.isXtreamAuthorized(user, pass)) {
                            call.respondText("Authentication failed", status = HttpStatusCode.Unauthorized)
                            return@get
                        }
                        val parts = call.parameters["streamId"].orEmpty().split('.')
                        if (parts.size >= 3) {
                            call.respondRedirect(
                                "/vod/series/${parts[0]}/${parts[1]}/${parts[2]}.m3u8",
                                permanent = false,
                            )
                        }
                    }
                }
                route("/dlhd-event-stream/{token}.m3u8") {
                    get { dlhdEventStreamRoutes.eventStream(call, call.parameters["token"].orEmpty()) }
                    head { dlhdEventStreamRoutes.eventStream(call, call.parameters["token"].orEmpty()) }
                }
                route("/dlhd-event-guide/{slug}.html") {
                    get { dlhdEventStreamRoutes.guidePage(call, call.parameters["slug"].orEmpty()) }
                    head { dlhdEventStreamRoutes.guidePage(call, call.parameters["slug"].orEmpty()) }
                }
                route("/dlhd-event-guide/{slug}.m3u8") {
                    get { dlhdEventStreamRoutes.guideStream(call, call.parameters["slug"].orEmpty()) }
                    head { dlhdEventStreamRoutes.guideStream(call, call.parameters["slug"].orEmpty()) }
                }
                route("/dlhd-event-guide/{slug}.mp4") {
                    get { dlhdEventStreamRoutes.guideMp4(call, call.parameters["slug"].orEmpty()) }
                    head { dlhdEventStreamRoutes.guideMp4(call, call.parameters["slug"].orEmpty()) }
                }
                route("/dlhd-event-guide/slate.m3u8") {
                    get { dlhdEventStreamRoutes.guideStreamLegacy(call) }
                    head { dlhdEventStreamRoutes.guideStreamLegacy(call) }
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
                route("/sports-epg.xml") {
                    get { epgRoutes.sportsEpgXml(call) }
                    head { epgRoutes.sportsEpgXml(call) }
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
