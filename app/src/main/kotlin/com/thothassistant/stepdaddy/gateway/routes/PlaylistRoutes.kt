package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.FireMemoryGuard
import com.thothassistant.stepdaddy.gateway.epg.EpgPlaylistUrlResolver
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamHealthStore
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
    private val eventHealthStore: DlhdEventStreamHealthStore,
) {
    /** Full TiviMate catalog — canonical user playlist. */
    suspend fun tivimateUserPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_USER, "playlist_unavailable") {
            buildPlaylistBody()
        }
    }

    /** Full StreamVault catalog — canonical user playlist. */
    suspend fun streamVaultUserPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_USER, "streamvault_playlist_unavailable") {
            buildStreamVaultPlaylistBody()
        }
    }

    /** Full generic-player catalog (plain proxy URLs) — canonical user playlist. */
    suspend fun vlcUserPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_USER, "vlc_playlist_unavailable") {
            buildVlcPlaylistBody()
        }
    }

    /** Xtream Codes get.php — live M3U for TiviMate Xtream login / URL import. */
    suspend fun xtreamGetPhp(call: ApplicationCall) {
        val username = call.request.queryParameters["username"].orEmpty()
        val password = call.request.queryParameters["password"].orEmpty()
        if (!environment.isXtreamAuthorized(username, password)) {
            call.respondText("Authentication failed", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            return
        }
        respondPlaylist(call, PlaylistPaths.KIND_USER, "playlist_unavailable") {
            buildPlaylistBody()
        }
    }

    /** Legacy alias — same body as [tivimateUserPlaylist], marked diagnostic. */
    suspend fun tivimatePlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_DIAGNOSTIC, "playlist_unavailable") {
            buildPlaylistBody()
        }
    }

    /** Legacy alias — same body as [streamVaultUserPlaylist], marked diagnostic. */
    suspend fun streamVaultPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_DIAGNOSTIC, "streamvault_playlist_unavailable") {
            buildStreamVaultPlaylistBody()
        }
    }

    /** 50-channel bootstrap — diagnostic only (TiviMate FUSA / wizard probes). */
    suspend fun tivimateSetupPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_DIAGNOSTIC, "setup_playlist_unavailable") {
            buildSetupPlaylistBody()
        }
    }

    /** Legacy path — full StreamVault catalog, marked diagnostic for tooling. */
    suspend fun streamVaultSetupPlaylist(call: ApplicationCall) {
        respondPlaylist(call, PlaylistPaths.KIND_DIAGNOSTIC, "streamvault_setup_playlist_unavailable") {
            buildStreamVaultPlaylistBody()
        }
    }

    fun schedulePrewarm() {
        // Fire Stick: building two full M3Us spikes RAM and trips LMK; build on first request.
        if (FireMemoryGuard.deferHeavyBootWork(environment.appContext)) return
        val channels = client.channels
        val supplements = supplementSource.channels()
        val channelCount = channels.size
        val supplementCount = supplements.size
        playlistCache.schedulePrewarm(
            playlistCacheKey(channelCount, supplementCount, PlaylistCache.FLAVOR_TIVIMATE),
        ) {
            buildPlaylistBodySync(channels, supplements)
        }
        playlistCache.schedulePrewarm(
            playlistCacheKey(channelCount, supplementCount, PlaylistCache.FLAVOR_STREAMVAULT),
        ) {
            buildStreamVaultPlaylistBodySync(channels, supplements)
        }
    }

    private suspend fun respondPlaylist(
        call: ApplicationCall,
        kind: String,
        errorCode: String,
        bodyBuilder: suspend () -> String,
    ) {
        try {
            val body = bodyBuilder()
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
            call.response.header(HttpHeaders.Pragma, "no-cache")
            call.response.header(PlaylistPaths.HEADER_KIND, kind)
            call.respondText(body, ContentType("application", "vnd.apple.mpegurl"))
        } catch (exc: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (exc.message ?: errorCode)),
            )
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
            playlistEpgUrl = resolvedPlaylistEpgUrl(),
            playlistEpgUrlKey = EpgPlaylistUrlResolver.playlistCacheKey(environment),
            eventHealthRevision = eventHealthStore.revision(),
        )
        playlistCache.getOrBuild(cacheKey) {
            buildPlaylistBodySync(channels, supplements)
        }
    }

    private suspend fun buildSetupPlaylistBody(): String = withContext(Dispatchers.IO) {
        val channels = client.channels
        val supplements = supplementSource.channels()
        buildSetupPlaylistBodySync(channels, supplements)
    }

    private suspend fun buildStreamVaultPlaylistBody(): String = withContext(Dispatchers.IO) {
        val channels = client.channels
        val supplements = supplementSource.channels()
        val cacheKey = streamVaultPlaylistCacheKey(channels.size, supplements.size)
        playlistCache.getOrBuild(cacheKey) {
            buildStreamVaultPlaylistBodySync(channels, supplements)
        }
    }

    private suspend fun buildVlcPlaylistBody(): String = buildStreamVaultPlaylistBody()

    private fun streamVaultPlaylistCacheKey(channelCount: Int, supplementCount: Int): Long =
        playlistCacheKey(channelCount, supplementCount, PlaylistCache.FLAVOR_STREAMVAULT)

    private fun playlistCacheKey(
        channelCount: Int,
        supplementCount: Int,
        playlistFlavor: Int,
    ): Long = playlistCache.computeKey(
        channelCount = channelCount,
        supplementCount = supplementCount,
        supplementSyncedAtMs = supplementSource.lastSyncedAtMs(),
        channelRevision = client.channelRevision(),
        logoDbLoaded = logoResolver.isLoaded(),
        playlistTitleStyle = environment.playlistTitleStyle,
        playlistEpgUrl = resolvedPlaylistEpgUrl(),
        playlistEpgUrlKey = EpgPlaylistUrlResolver.playlistCacheKey(environment),
        playlistFlavor = playlistFlavor,
        eventHealthRevision = eventHealthStore.revision(),
    )

    private fun buildStreamVaultPlaylistBodySync(
        channels: List<com.thothassistant.stepdaddy.gateway.model.Channel>,
        supplements: List<com.thothassistant.stepdaddy.gateway.model.SupplementChannel>,
    ): String {
        val base = environment.loopbackBase()
        val epgUrl = resolvedPlaylistEpgUrl()
        if (channels.isEmpty()) {
            return PlaylistBuilder.minimalPlaylist(base, epgUrl)
        }
        return PlaylistBuilder.streamVaultPlaylist(
            channels = channels,
            baseUrl = base,
            dlhdOrigin = client.activeBaseUrl,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = environment.playlistTitleStyle,
            epgUrl = epgUrl,
            eventHealthStore = eventHealthStore,
        )
    }

    private fun resolvedPlaylistEpgUrl(): String? =
        EpgPlaylistUrlResolver.resolve(environment, supplementSource.sportsEpgXmlFile())

    private fun buildPlaylistBodySync(
        channels: List<com.thothassistant.stepdaddy.gateway.model.Channel>,
        supplements: List<com.thothassistant.stepdaddy.gateway.model.SupplementChannel>,
    ): String {
        val base = environment.loopbackBase()
        val epgUrl = resolvedPlaylistEpgUrl()
        if (channels.isEmpty()) {
            return PlaylistBuilder.minimalPlaylist(base, epgUrl)
        }
        return PlaylistBuilder.tivimatePlaylist(
            channels = channels,
            baseUrl = base,
            dlhdOrigin = client.activeBaseUrl,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = environment.playlistTitleStyle,
            epgUrl = epgUrl,
            eventHealthStore = eventHealthStore,
        )
    }

    private fun buildSetupPlaylistBodySync(
        channels: List<com.thothassistant.stepdaddy.gateway.model.Channel>,
        supplements: List<com.thothassistant.stepdaddy.gateway.model.SupplementChannel>,
    ): String {
        val base = environment.loopbackBase()
        val epgUrl = resolvedPlaylistEpgUrl()
        if (channels.isEmpty()) {
            return PlaylistBuilder.minimalPlaylist(base, epgUrl)
        }
        return PlaylistBuilder.tivimateSetupPlaylist(
            channels = channels,
            baseUrl = base,
            dlhdOrigin = client.activeBaseUrl,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = environment.playlistTitleStyle,
            epgUrl = epgUrl,
            eventHealthStore = eventHealthStore,
        )
    }
}
