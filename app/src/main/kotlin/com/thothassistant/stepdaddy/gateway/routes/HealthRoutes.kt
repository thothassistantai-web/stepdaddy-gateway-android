package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgCoverageCalculator
import com.thothassistant.stepdaddy.gateway.epg.EpgPlaylistUrlResolver
import com.thothassistant.stepdaddy.gateway.model.CanaryStatus
import com.thothassistant.stepdaddy.gateway.model.CategoryCount
import com.thothassistant.stepdaddy.gateway.model.ProviderStats
import com.thothassistant.stepdaddy.gateway.model.SupplementStatus
import com.thothassistant.stepdaddy.gateway.model.HealingStatus
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.TivimateSetup
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardLoadProgressCalculator
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val supplementSource: SupplementSource,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun health(call: ApplicationCall) {
        val healing = client.healingSnapshot()
        val channelCount = client.channels.size
        val sync = supplementSource.syncSnapshot()
        val adultCount = client.channels.count { channel ->
            GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle == GroupTitleResolver.ADULT
        }
        val topCategories = client.channels
            .groupingBy { GroupTitleResolver.resolve(it.name, it.tags).groupTitle }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { CategoryCount(it.key, it.value) }
        val supplementCount = supplementSource.channelCount()
        val totalChannels = channelCount + supplementCount
        val gatewayEpgOn = environment.gatewayEpgEnabled
        val playlistEpgUrls = EpgPlaylistUrlResolver.resolvePlaylistEpgUrls(
            environment,
            supplementSource.sportsEpgXmlFile(),
        )
        val supplementStatus = SupplementStatus(
                enabled = supplementSource.enabled(),
                sidecarEnabled = supplementSource.sidecarEnabled(),
                sportsEnabled = supplementSource.sportsEnabled(),
                iptvOrgEnabled = supplementSource.iptvOrgEnabled(),
                ntvCxEnabled = supplementSource.ntvCxEnabled(),
                adultSwimEnabled = supplementSource.adultSwimEnabled(),
                channels = supplementSource.channelCount(),
                moveOnJoyChannels = supplementSource.moveOnJoyCount(),
                sportsChannels = supplementSource.sportsCount(),
                sportsEventsScanned = sync.sportsEventsScanned,
                supplementSyncInFlight = supplementSource.syncInFlight(),
                iptvOrgChannels = supplementSource.iptvOrgCount(),
                ntvCxChannels = supplementSource.ntvCxCount(),
                adultSwimChannels = supplementSource.adultSwimCount(),
                ntvCxImportMode = supplementSource.ntvCxImportMode().name,
                ntvCxMergeMode = supplementSource.ntvCxImportMode().name,
                sidecarImportMode = environment.supplementSidecarImportMode.name,
                iptvOrgImportMode = environment.supplementIptvOrgImportMode.name,
                adultSwimImportMode = environment.supplementAdultSwimImportMode.name,
                ntvCxResolveProbeOk = sync.ntvCxResolveProbeOk,
                adultSwimProbed = sync.adultSwimProbed,
                adultSwimProbeOk = sync.adultSwimProbeOk,
                iptvOrgPlaylistsFetched = sync.iptvOrgPlaylistsFetched,
                iptvOrgPlaylistsFailed = sync.iptvOrgPlaylistsFailed,
                blockedTheTvApp = sync.blockedTheTvApp,
                blockedTvPass = sync.blockedTvPass,
                blockedTokenProxy = sync.blockedTokenProxy,
            )
        val providerStats = ProviderStats(
                daddylive = channelCount,
                moveOnJoy = supplementSource.moveOnJoyCount(),
                iptvOrg = supplementSource.iptvOrgCount(),
                sports = supplementSource.sportsCount(),
                ntvCx = supplementSource.ntvCxCount(),
                adultSwim = supplementSource.adultSwimCount(),
                adult = adultCount,
                total = totalChannels,
            )
        val epgReady = if (gatewayEpgOn) epgManager.epgReady() else playlistEpgUrls.isNotEmpty()
        val basePayload = HealthResponse(
            ok = true,
            starting = totalChannels == 0,
            version = BuildConfig.VERSION_NAME,
            channels = channelCount,
            port = environment.port,
            baseUrl = environment.loopbackBase(),
            upstreamBaseUrl = client.activeBaseUrl,
            gatewayEpgEnabled = gatewayEpgOn,
            epgExternal = !gatewayEpgOn,
            epgSourceCount = playlistEpgUrls.size,
            epgReady = epgReady,
            epgProgrammeCount = if (gatewayEpgOn) {
                epgManager.programmeCount()
            } else {
                playlistEpgUrls.size
            },
            epgAgeSeconds = if (gatewayEpgOn) epgManager.ageSeconds() else null,
            epgCoverage = if (gatewayEpgOn) {
                EpgCoverageCalculator.snapshot(
                    channels = client.channels,
                    supplementSource = supplementSource,
                    meta = epgManager.meta,
                )
            } else {
                null
            },
            supplementEnabled = supplementSource.enabled(),
            supplementChannels = supplementCount,
            supplement = supplementStatus,
            providers = providerStats,
            topCategories = topCategories,
            healing = HealingStatus(
                lastAction = healing.lastAction,
                streamFailures = healing.streamFailureCount,
                deadMirrors = healing.deadMirrorCount,
                streamCacheEntries = healing.streamCacheSize,
                upstreamCacheEntries = healing.upstreamCacheSize,
                staleDiskEntries = healing.staleDiskEntries,
                outageMode = healing.outageMode,
                cacheServeMode = healing.cacheServeMode,
                breakerOpen = healing.breakerOpen,
                breakerRemainingMs = healing.breakerRemainingMs,
                outageOpenCount = healing.outageOpenCount,
                lastUpstreamSuccessMs = healing.lastUpstreamSuccessMs,
                canary = CanaryStatus(
                    goodOk = healing.canary.goodOk,
                    goodTotal = healing.canary.goodTotal,
                    badExpectedFail = healing.canary.badExpectedFail,
                    badTotal = healing.canary.badTotal,
                    lastProbeMs = healing.canary.lastProbeMs,
                ),
                recentActions = healing.recentActions.takeLast(5),
            ),
        )
        val gatewayOnline = totalChannels > 0 && !basePayload.starting &&
            (!gatewayEpgOn || epgReady || !epgManager.isBuilding())
        val payload = basePayload.copy(
            loadProgress = DashboardLoadProgressCalculator.snapshot(
                health = basePayload,
                epgManager = epgManager,
                gatewayOnline = gatewayOnline,
                serviceActive = true,
            ),
        )
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
        )
    }

    suspend fun tivimateSetup(call: ApplicationCall) {
        val base = environment.loopbackBase()
        val playlistEpgUrls = EpgPlaylistUrlResolver.resolvePlaylistEpgUrls(
            environment,
            supplementSource.sportsEpgXmlFile(),
        )
        val gatewayEpgOn = environment.gatewayEpgEnabled
        val payload = TivimateSetup(
            playlist = "$base/tivimate-playlist.m3u8",
            epg = playlistEpgUrls.joinToString(","),
            health = "$base/health",
            hint = if (gatewayEpgOn) {
                "Add the playlist URL in TiviMate using 127.0.0.1 on this device."
            } else {
                "Gateway EPG is disabled. TiviMate loads EPG from the external URLs in the playlist."
            },
            epgReady = if (gatewayEpgOn) epgManager.epgReady() else playlistEpgUrls.isNotEmpty(),
            epgProgrammeCount = if (gatewayEpgOn) {
                epgManager.programmeCount()
            } else {
                playlistEpgUrls.size
            },
            epgAgeSeconds = if (gatewayEpgOn) epgManager.ageSeconds() else null,
        )
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
        )
    }
}
