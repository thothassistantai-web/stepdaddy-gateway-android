package com.thothassistant.stepdaddy.gateway.routes

import android.content.Context
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.TiviMateEventStore
import com.thothassistant.stepdaddy.gateway.StreamVaultController
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.streamvault.StreamVaultPluginContract
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgCoverageCalculator
import com.thothassistant.stepdaddy.gateway.epg.EpgPlaylistUrlResolver
import com.thothassistant.stepdaddy.gateway.audio.AudioPlaybackSettings
import com.thothassistant.stepdaddy.gateway.model.AudioPlaybackPrefs
import com.thothassistant.stepdaddy.gateway.model.CanaryStatus
import com.thothassistant.stepdaddy.gateway.model.CategoryCount
import com.thothassistant.stepdaddy.gateway.model.ProviderStats
import com.thothassistant.stepdaddy.gateway.model.SupplementStatus
import com.thothassistant.stepdaddy.gateway.model.HealingStatus
import com.thothassistant.stepdaddy.gateway.model.MirrorStats
import com.thothassistant.stepdaddy.gateway.model.SpecialEventMirrorEventStats
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.TiviMateHealthEvents
import com.thothassistant.stepdaddy.gateway.model.StreamVaultSetup
import com.thothassistant.stepdaddy.gateway.model.TivimateSetup
import com.thothassistant.stepdaddy.gateway.relay.DomainRelayRuntime
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSyncSnapshot
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsHealthSummary
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardLoadProgressCalculator
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthRoutes(
    private val appContext: Context,
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val supplementSource: SupplementSource,
    private val playlistCache: PlaylistCache,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun health(call: ApplicationCall) {
        if (call.request.queryParameters["lite"] == "1") {
            val payload = withContext(Dispatchers.Default) { buildLiteHealthPayload() }
            call.respondText(json.encodeToString(payload), ContentType.Application.Json)
            return
        }
        val payload = withContext(Dispatchers.Default) { buildFullHealthPayload() }
        call.respondText(json.encodeToString(payload), ContentType.Application.Json)
    }

    /** Fast loopback probe — includes dashboard stats without expensive category/healing scans. */
    private fun buildLiteHealthPayload(): HealthResponse {
        val channelCount = client.channels.size
        val supplementCount = supplementSource.channelCount()
        val totalChannels = channelCount + supplementCount
        val gatewayEpgOn = environment.gatewayEpgEnabled
        val sync = supplementSource.syncSnapshot()
        val supplementStatus = buildSupplementStatus(sync)
        val providerStats = buildProviderStats(channelCount, totalChannels)
        val epgReady = if (gatewayEpgOn) epgManager.epgReady() else false
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
            epgReady = epgReady,
            epgProgrammeCount = if (gatewayEpgOn) epgManager.programmeCount() else 0,
            epgAgeSeconds = if (gatewayEpgOn) epgManager.ageSeconds() else null,
            supplementEnabled = supplementSource.enabled(),
            supplementChannels = supplementCount,
            supplement = supplementStatus,
            providers = providerStats,
            audio = buildAudioPlaybackPrefs(),
        )
        val gatewayOnline = totalChannels > 0 && !basePayload.starting
        return basePayload.copy(
            loadProgress = DashboardLoadProgressCalculator.snapshot(
                health = basePayload,
                epgManager = epgManager,
                gatewayOnline = gatewayOnline,
                serviceActive = true,
            ),
            mirrorStats = buildMirrorStats(),
        ).withDomainRelay()
    }

    private fun buildFullHealthPayload(): HealthResponse {
        val healing = client.healingSnapshot()
        val channelCount = client.channels.size
        val sync = supplementSource.syncSnapshot()
        val specialEventGuides = sync.specialEventGuides
            .takeIf { it > 0 }
            ?: supplementSource.specialEventGuideCount()
        val dlhdEventStreams = sync.dlhdEventStreams
            .takeIf { it > 0 }
            ?: supplementSource.dlhdEventStreamCount()
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
        val supplementStatus = buildSupplementStatus(
            sync = sync,
            specialEventGuides = specialEventGuides,
            dlhdEventStreams = dlhdEventStreams,
        )
        val providerStats = buildProviderStats(
            channelCount = channelCount,
            totalChannels = totalChannels,
            adultCount = adultCount,
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
            audio = buildAudioPlaybackPrefs(),
        )
        val gatewayOnline = totalChannels > 0 && !basePayload.starting
        val lastTiviMateEvent = TiviMateEventStore.lastEvent()
        return basePayload.copy(
            loadProgress = DashboardLoadProgressCalculator.snapshot(
                health = basePayload,
                epgManager = epgManager,
                gatewayOnline = gatewayOnline,
                serviceActive = true,
            ),
            tivimateEvents = TiviMateHealthEvents(
                buffered = TiviMateEventStore.size(),
                lastEvent = lastTiviMateEvent?.event,
                lastTimestamp = lastTiviMateEvent?.timestamp,
            ),
            mirrorStats = buildMirrorStats(),
        ).withDomainRelay()
    }

    private fun HealthResponse.withDomainRelay(): HealthResponse {
        val snap = DomainRelayRuntime.healthSnapshot(
            userCustomizedPrimary = environment.hasUserCustomizedDlhdBaseUrl,
            userCustomizedMirrors = environment.hasUserCustomizedMirrorUrls,
        )
        return copy(
            domainRelayActive = snap.active,
            domainRelayVersion = snap.version,
            domainRelaySource = snap.source,
            domainRelayFetchedAtMs = snap.fetchedAtMs,
            domainRelay = snap,
            vodCatalogRelay = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.healthSnapshot(),
        )
    }

    private fun buildMirrorStats(): MirrorStats? {
        val upstream = client.mirrorStatsSnapshot()
        val mirrorHealth = supplementSource.specialEventsMirrorSummary()
        val hasSpecialEvents = mirrorHealth.eventsWithMirrors > 0
        val hasUpstreamMirrors = upstream.mirrorLatenciesMs.isNotEmpty() ||
            upstream.activeBaseUrl.isNotBlank()
        if (!hasSpecialEvents && !hasUpstreamMirrors) {
            return null
        }
        return MirrorStats(
            activeBaseUrl = upstream.activeBaseUrl,
            fastestMirrorEmaMs = upstream.fastestMirrorEmaMs,
            streamCacheHitRate = upstream.streamCacheHitRate,
            mirrorLatenciesMs = upstream.mirrorLatenciesMs,
            specialEventMirrorsTotal = mirrorHealth.totalMirrors,
            specialEventMirrorsHealthy = mirrorHealth.healthyMirrors,
            specialEventMirrorEvents = mirrorHealth.eventsWithMirrors,
            specialEventAvgMirrorsPerEvent = mirrorHealth.avgMirrorsPerEvent,
            specialEventMirrorDetails = mirrorHealth.events.map { event ->
                SpecialEventMirrorEventStats(
                    eventKey = event.eventKey,
                    mirrorsTotal = event.mirrorsTotal,
                    mirrorsHealthy = event.mirrorsHealthy,
                    activeMirrorIndex = event.activeMirrorIndex,
                )
            },
        )
    }

    suspend fun tivimateSetup(call: ApplicationCall) {
        val payload = withContext(Dispatchers.Default) { buildTivimateSetupPayload() }
        call.respondText(json.encodeToString(payload), ContentType.Application.Json)
    }

    suspend fun streamvaultSetup(call: ApplicationCall) {
        val payload = withContext(Dispatchers.Default) { buildStreamVaultSetupPayload() }
        call.respondText(json.encodeToString(payload), ContentType.Application.Json)
    }

    private fun buildTivimateSetupPayload(): TivimateSetup {
        val base = environment.loopbackBase()
        val playlistEpgUrls = EpgPlaylistUrlResolver.resolvePlaylistEpgUrls(
            environment,
            supplementSource.sportsEpgXmlFile(),
        )
        val gatewayEpgOn = environment.gatewayEpgEnabled
        val player = TiviMateController.probe(appContext)
        return TivimateSetup(
            playlist = "$base${PlaylistPaths.TIVIMATE}",
            playlistDiagnostic = "$base${PlaylistPaths.TIVIMATE_SETUP}",
            playlistSmart = "$base${PlaylistPaths.TIVIMATE_SMART}",
            epg = playlistEpgUrls.joinToString(","),
            health = "$base/health",
            xtreamServer = base,
            xtreamUsername = environment.xtreamUsername,
            xtreamPassword = environment.xtreamPassword,
            hint = if (gatewayEpgOn) {
                "Xtream login: Server $base, user ${environment.xtreamUsername}, " +
                    "pass ${environment.xtreamPassword}. Gateway auto-imports on launch for x2 mod. " +
                    "Recommended M3U (fast): $base${PlaylistPaths.TIVIMATE}. " +
                    "Optional Smart (backups): $base${PlaylistPaths.TIVIMATE_SMART}."
            } else {
                "Xtream: Server $base, user ${environment.xtreamUsername}, " +
                    "pass ${environment.xtreamPassword} (Movies/Series tabs). " +
                    "M3U fast: $base${PlaylistPaths.TIVIMATE}; " +
                    "Smart backups: $base${PlaylistPaths.TIVIMATE_SMART}."
            },
            epgReady = if (gatewayEpgOn) epgManager.epgReady() else playlistEpgUrls.isNotEmpty(),
            epgProgrammeCount = if (gatewayEpgOn) {
                epgManager.programmeCount()
            } else {
                playlistEpgUrls.size
            },
            epgAgeSeconds = if (gatewayEpgOn) epgManager.ageSeconds() else null,
            playerInstalled = player.installed,
            playerVersion = player.versionName,
            playerVersionCode = player.versionCode,
            playerLikelyActive = player.likelyActive,
            launchComponent = TiviMateController.launchComponent(appContext),
            audio = buildAudioPlaybackPrefs(),
        )
    }

    private fun buildStreamVaultSetupPayload(): StreamVaultSetup {
        val base = environment.loopbackBase()
        val playlistEpgUrls = EpgPlaylistUrlResolver.resolvePlaylistEpgUrls(
            environment,
            supplementSource.sportsEpgXmlFile(),
        )
        val gatewayEpgOn = environment.gatewayEpgEnabled
        val player = StreamVaultController.probe(appContext)
        val gatewayPackage = appContext.packageName
        return StreamVaultSetup(
            playlist = "$base${PlaylistPaths.STREAMVAULT}",
            playlistDiagnostic = "$base${PlaylistPaths.STREAMVAULT_SETUP}",
            epg = playlistEpgUrls.joinToString(","),
            health = "$base/health",
            hint = "Enable the StepDaddy Gateway plugin in StreamVault, or paste " +
                "$base${PlaylistPaths.STREAMVAULT} in Provider Setup. " +
                "Legacy $base${PlaylistPaths.STREAMVAULT_SETUP} still works.",
            pluginId = StreamVaultPluginContract.PLUGIN_ID,
            pluginService = "$gatewayPackage/.streamvault.StreamVaultPluginService",
            epgReady = if (gatewayEpgOn) epgManager.epgReady() else playlistEpgUrls.isNotEmpty(),
            epgProgrammeCount = if (gatewayEpgOn) {
                epgManager.programmeCount()
            } else {
                playlistEpgUrls.size
            },
            epgAgeSeconds = if (gatewayEpgOn) epgManager.ageSeconds() else null,
            playerInstalled = player.installed,
            playerVersion = player.versionName,
            playerVersionCode = player.versionCode,
            launchComponent = StreamVaultController.launchComponent(appContext),
            audio = buildAudioPlaybackPrefs(),
        )
    }

    private fun buildAudioPlaybackPrefs(): AudioPlaybackPrefs =
        AudioPlaybackSettings.fromEnvironment(environment)

    private fun buildSupplementStatus(
        sync: SupplementSyncSnapshot,
        specialEventGuides: Int = sync.specialEventGuides
            .takeIf { it > 0 }
            ?: supplementSource.specialEventGuideCount(),
        dlhdEventStreams: Int = sync.dlhdEventStreams
            .takeIf { it > 0 }
            ?: supplementSource.dlhdEventStreamCount(),
    ): SupplementStatus {
        val sportsOn = supplementSource.sportsEnabled()
        val syncInFlight = supplementSource.syncInFlight()
        val lastSyncMs = supplementSource.specialEventsLastSyncMs().takeIf { it > 0L }
        val nowMs = System.currentTimeMillis()
        val stale = SpecialEventsHealthSummary.isStale(lastSyncMs, nowMs)
        val status = SpecialEventsHealthSummary.status(
            sportsEnabled = sportsOn,
            syncInFlight = supplementSource.specialEventsSyncInFlight(),
            guideCount = specialEventGuides,
            liveEventCount = dlhdEventStreams,
            lastSyncMs = lastSyncMs,
            nowMs = nowMs,
        )
        val eventHealth = supplementSource.dlhdEventStreamHealthSummary()
        val mirrorHealth = supplementSource.specialEventsMirrorSummary()
        return SupplementStatus(
            enabled = supplementSource.enabled(),
            sportsEnabled = sportsOn,
            iptvOrgEnabled = supplementSource.iptvOrgEnabled(),
            ntvCxEnabled = supplementSource.ntvCxEnabled(),
            adultSwimEnabled = supplementSource.adultSwimEnabled(),
            freeTvEnabled = supplementSource.freeTvEnabled(),
            duloCxEnabled = supplementSource.duloCxEnabled(),
            channels = supplementSource.channelCount(),
            sportsChannels = supplementSource.sportsCount(),
            specialEventGuides = specialEventGuides,
            dlhdEventStreams = dlhdEventStreams,
            sportsEventsScanned = sync.sportsEventsScanned,
            supplementSyncInFlight = syncInFlight,
            iptvOrgChannels = maxOf(supplementSource.iptvOrgCount(), sync.iptvOrgChannels),
            ntvCxChannels = supplementSource.ntvCxCount(),
            adultSwimChannels = supplementSource.adultSwimCount(),
            freeTvChannels = maxOf(supplementSource.freeTvCount(), sync.freeTvChannels),
            duloCxChannels = maxOf(supplementSource.duloCxCount(), sync.duloCxChannels),
            ntvCxImportMode = supplementSource.ntvCxImportMode().name,
            ntvCxMergeMode = supplementSource.ntvCxImportMode().name,
            iptvOrgImportMode = environment.supplementIptvOrgImportMode.name,
            adultSwimImportMode = environment.supplementAdultSwimImportMode.name,
            freeTvImportMode = environment.supplementFreeTvImportMode.name,
            duloCxImportMode = environment.supplementDuloCxImportMode.name,
            iptvOrgEnabledPlaylistCount = environment.iptvOrgEnabledPlaylists.size,
            ntvCxResolveProbeOk = sync.ntvCxResolveProbeOk,
            adultSwimProbed = sync.adultSwimProbed,
            adultSwimProbeOk = sync.adultSwimProbeOk,
            freeTvPlaylistsFetched = sync.freeTvPlaylistsFetched,
            freeTvPlaylistsFailed = sync.freeTvPlaylistsFailed,
            duloCxCatalogFetchOk = sync.duloCxCatalogFetchOk,
            duloCxResolveProbeOk = sync.duloCxResolveProbeOk,
            duloCxAuthConfigured = sync.duloCxAuthConfigured ||
                environment.supplementDuloCxAccessToken.isNotBlank(),
            tmdbMoviesEnabled = supplementSource.tmdbMoviesEnabled(),
            tmdbVodMovies = supplementSource.tmdbVodCount().takeIf { it > 0 } ?: sync.tmdbVodMovies,
            tmdbVodSeries = supplementSource.tmdbVodSeriesCount().takeIf { it > 0 } ?: sync.tmdbVodSeries,
            vodCatalogRelayActive = sync.vodCatalogRelayActive,
            vodCatalogRelayVersion = sync.vodCatalogRelayVersion,
            vodCatalogRelayMovies = sync.vodCatalogRelayMovies,
            vodCatalogRelayShows = sync.vodCatalogRelayShows,
            vodCatalogRelayProbed = sync.vodCatalogRelayProbed,
            vodCatalogRelayProbeOk = sync.vodCatalogRelayProbeOk,
            vodCatalogRelayDeadPruned = sync.vodCatalogRelayDeadPruned,
            iptvOrgPlaylistsFetched = sync.iptvOrgPlaylistsFetched,
            iptvOrgPlaylistsFailed = sync.iptvOrgPlaylistsFailed,
            blockedTokenProxy = sync.blockedTokenProxy,
            lastSpecialEventsSyncMs = lastSyncMs,
            specialEventsScrapeAgeSeconds = SpecialEventsHealthSummary.ageSeconds(lastSyncMs, nowMs),
            specialEventsStale = stale,
            specialEventsStatus = status,
            dlhdEventHealthProbed = eventHealth.probed,
            dlhdEventHealthOk = eventHealth.healthy,
            dlhdEventHealthFailed = eventHealth.unhealthy,
            dlhdEventHealthUnknown = eventHealth.unknown,
            dlhdEventHealthLastProbeMs = eventHealth.lastProbeMs,
            specialEventMirrorsTotal = mirrorHealth.totalMirrors,
            specialEventMirrorsHealthy = mirrorHealth.healthyMirrors,
            specialEventMirrorEvents = mirrorHealth.eventsWithMirrors,
            specialEventAvgMirrorsPerEvent = mirrorHealth.avgMirrorsPerEvent,
            daddyBackupChannels = supplementSource.daddyChannelFallbacksAll().size,
            daddyBackupMirrors = supplementSource.daddyChannelFallbacksAll().values.sumOf { it.size },
        )
    }

    private fun buildProviderStats(
        channelCount: Int,
        totalChannels: Int,
        adultCount: Int = 0,
    ): ProviderStats {
        val playlistReady = playlistCache.cachedEntryCount().takeIf { it > 0 }
            ?: (totalChannels.takeIf { it > 0 } ?: 0)
        return ProviderStats(
            daddylive = channelCount,
            iptvOrg = maxOf(
                supplementSource.iptvOrgCount(),
                supplementSource.syncSnapshot().iptvOrgChannels,
            ),
            sports = supplementSource.sportsCount(),
            ntvCx = supplementSource.ntvCxCount(),
            adultSwim = supplementSource.adultSwimCount(),
            freeTv = supplementSource.freeTvCount(),
            duloCx = supplementSource.duloCxCount(),
            adult = adultCount,
            playlistReady = playlistReady,
            total = totalChannels,
        )
    }
}
