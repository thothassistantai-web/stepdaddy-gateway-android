package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpstreamChannelRow(
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("channel_name") val channelName: String = "",
    /** Present on current DaddyLive API: `/player/embed.php?id=…` */
    val url: String = "",
) {
    fun resolvedChannelId(): String =
        com.thothassistant.stepdaddy.gateway.upstream.DlhdEmbedUrl.channelIdFromRow(channelId, url)

    fun resolvedEmbedUrl(): String? = url.trim().takeIf { it.isNotEmpty() }
}

data class Channel(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val logo: String? = null,
    val tvgId: String? = null,
    /** Upstream embed page from `/api/channels` when available. */
    val embedUrl: String? = null,
)

data class UpstreamManifest(
    val playlistText: String,
    val masterUrl: String,
    val refererHost: String,
)

@Serializable
data class CanaryStatus(
    val goodOk: Int = 0,
    val goodTotal: Int = 0,
    val badExpectedFail: Int = 0,
    val badTotal: Int = 0,
    val lastProbeMs: Long = 0L,
)

@Serializable
data class HealingStatus(
    val lastAction: String = "none",
    val streamFailures: Int = 0,
    val deadMirrors: Int = 0,
    val streamCacheEntries: Int = 0,
    val upstreamCacheEntries: Int = 0,
    val staleDiskEntries: Int = 0,
    val outageMode: Boolean = false,
    val cacheServeMode: Boolean = false,
    val breakerOpen: Boolean = false,
    val breakerRemainingMs: Long = 0L,
    val outageOpenCount: Int = 0,
    val lastUpstreamSuccessMs: Long? = null,
    val canary: CanaryStatus? = null,
    val recentActions: List<String> = emptyList(),
)

@Serializable
data class SupplementStatus(
    val enabled: Boolean = false,
    val sportsEnabled: Boolean = false,
    val iptvOrgEnabled: Boolean = false,
    val channels: Int = 0,
    val sportsChannels: Int = 0,
    val specialEventGuides: Int = 0,
    val dlhdEventStreams: Int = 0,
    val sportsEventsScanned: Int = 0,
    val supplementSyncInFlight: Boolean = false,
    val iptvOrgChannels: Int = 0,
    val iptvOrgPlaylistsFetched: Int = 0,
    val iptvOrgPlaylistsFailed: Int = 0,
    val blockedTokenProxy: Int = 0,
    val ntvCxEnabled: Boolean = false,
    val ntvCxChannels: Int = 0,
    val iptvOrgImportMode: String = "FULL_CATALOG",
    val ntvCxImportMode: String = "FULL_CATALOG",
    /** @deprecated use [ntvCxImportMode] */
    val ntvCxMergeMode: String = "FULL_CATALOG",
    val ntvCxResolveProbeOk: Boolean = false,
    val adultSwimEnabled: Boolean = false,
    val adultSwimChannels: Int = 0,
    val adultSwimImportMode: String = "FULL_CATALOG",
    val freeTvEnabled: Boolean = false,
    val freeTvChannels: Int = 0,
    val freeTvImportMode: String = "FULL_CATALOG",
    val freeTvPlaylistsFetched: Int = 0,
    val freeTvPlaylistsFailed: Int = 0,
    val iptvOrgEnabledPlaylistCount: Int = 0,
    val adultSwimProbed: Int = 0,
    val adultSwimProbeOk: Int = 0,
    val tmdbMoviesEnabled: Boolean = false,
    val tmdbVodMovies: Int = 0,
    val tmdbVodSeries: Int = 0,
    /** Epoch ms of the last DaddyLive Special Events scrape. */
    val lastSpecialEventsSyncMs: Long? = null,
    /** Seconds since [lastSpecialEventsSyncMs]; null when never scraped. */
    val specialEventsScrapeAgeSeconds: Long? = null,
    /** True when scrape age exceeds the 15-minute refresh interval (+ grace). */
    val specialEventsStale: Boolean = false,
    /** Operator label: ok, stale, syncing, pending, empty, disabled. */
    val specialEventsStatus: String = "disabled",
    val dlhdEventHealthProbed: Int = 0,
    val dlhdEventHealthOk: Int = 0,
    val dlhdEventHealthFailed: Int = 0,
    val dlhdEventHealthUnknown: Int = 0,
    val dlhdEventHealthLastProbeMs: Long? = null,
    val specialEventMirrorsTotal: Int = 0,
    val specialEventMirrorsHealthy: Int = 0,
    val specialEventMirrorEvents: Int = 0,
    val specialEventAvgMirrorsPerEvent: Float = 0f,
)

@Serializable
data class ProviderStats(
    val daddylive: Int = 0,
    val iptvOrg: Int = 0,
    val sports: Int = 0,
    val ntvCx: Int = 0,
    val adultSwim: Int = 0,
    val freeTv: Int = 0,
    val adult: Int = 0,
    /** Channels in the warmed playlist cache (stream-ready); 0 until prewarm completes. */
    val playlistReady: Int = 0,
    val total: Int = 0,
)

@Serializable
data class CategoryCount(
    val groupTitle: String,
    val count: Int,
)

@Serializable
data class EpgCoverage(
    val playlistChannels: Int = 0,
    val withTvgId: Int = 0,
    val withProgrammes: Int = 0,
    val withRealProgrammes: Int = 0,
    val withPlaceholders: Int = 0,
    val unmapped: Int = 0,
    val supplementNoTvgId: Int = 0,
    val mappedPercent: Float = 0f,
    val programmePercent: Float = 0f,
    val placeholderProgrammes: Int = 0,
)

@Serializable
data class SpecialEventMirrorEventStats(
    val eventKey: String,
    val mirrorsTotal: Int,
    val mirrorsHealthy: Int,
    val activeMirrorIndex: Int,
)

@Serializable
data class MirrorStats(
    val activeBaseUrl: String = "",
    val fastestMirrorEmaMs: Double? = null,
    val streamCacheHitRate: Double? = null,
    val mirrorLatenciesMs: Map<String, Double> = emptyMap(),
    val specialEventMirrorsTotal: Int = 0,
    val specialEventMirrorsHealthy: Int = 0,
    val specialEventMirrorEvents: Int = 0,
    val specialEventAvgMirrorsPerEvent: Float = 0f,
    val specialEventMirrorDetails: List<SpecialEventMirrorEventStats> = emptyList(),
)

@Serializable
data class AudioPlaybackPrefs(
    val volumeNormalization: Boolean = false,
    val amplificationGainDb: Float = 0f,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    /** True while HTTP is up but the channel list is still empty (disk cache / upstream pending). */
    val starting: Boolean = false,
    val version: String,
    val channels: Int,
    val port: Int,
    val baseUrl: String,
    val upstreamBaseUrl: String,
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
    val gatewayEpgEnabled: Boolean = true,
    val epgExternal: Boolean = false,
    val epgSourceCount: Int = 0,
    val epgCoverage: EpgCoverage? = null,
    val supplementEnabled: Boolean = false,
    val supplementChannels: Int = 0,
    val supplement: SupplementStatus? = null,
    val providers: ProviderStats? = null,
    val topCategories: List<CategoryCount> = emptyList(),
    val healing: HealingStatus? = null,
    val loadProgress: DashboardLoadProgress? = null,
    val tivimateEvents: TiviMateHealthEvents? = null,
    val mirrorStats: MirrorStats? = null,
    val audio: AudioPlaybackPrefs? = null,
)

@Serializable
data class TivimateSetup(
    val playlist: String,
    val epg: String,
    val health: String,
    val hint: String,
    /** Xtream Codes login (recommended for separate Movies + Series tabs). */
    val xtreamServer: String = "",
    val xtreamUsername: String = "admin",
    val xtreamPassword: String = "password",
    /** Legacy diagnostic URL (bootstrap or superseded path); still served for backward compatibility. */
    val playlistDiagnostic: String = "",
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
    val playerInstalled: Boolean = false,
    val playerVersion: String? = null,
    val playerVersionCode: Long? = null,
    val playerLikelyActive: Boolean = false,
    val launchComponent: String = "ar.tvplayer.tv/com.andyhax.haxsplash.LaunchActivity",
    val audio: AudioPlaybackPrefs = AudioPlaybackPrefs(),
)

@Serializable
data class StreamVaultSetup(
    val playlist: String,
    val epg: String,
    val health: String,
    val hint: String,
    /** Legacy diagnostic URL; still served for backward compatibility. */
    val playlistDiagnostic: String = "",
    val pluginId: String,
    val pluginService: String,
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
    val playerInstalled: Boolean = false,
    val playerVersion: String? = null,
    val playerVersionCode: Long? = null,
    val launchComponent: String = "com.streamvault.app/com.streamvault.app.MainActivity",
    val audio: AudioPlaybackPrefs = AudioPlaybackPrefs(),
)
