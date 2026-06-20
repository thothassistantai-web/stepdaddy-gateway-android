package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpstreamChannelRow(
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("channel_name") val channelName: String = "",
)

data class Channel(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val logo: String? = null,
    val tvgId: String? = null,
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
    val sidecarEnabled: Boolean = false,
    val sportsEnabled: Boolean = false,
    val iptvOrgEnabled: Boolean = false,
    val channels: Int = 0,
    val moveOnJoyChannels: Int = 0,
    val sportsChannels: Int = 0,
    val iptvOrgChannels: Int = 0,
    val iptvOrgPlaylistsFetched: Int = 0,
    val iptvOrgPlaylistsFailed: Int = 0,
    val blockedTheTvApp: Int = 0,
    val blockedTvPass: Int = 0,
    val blockedTokenProxy: Int = 0,
    val ntvCxEnabled: Boolean = false,
    val ntvCxChannels: Int = 0,
    val ntvCxMergeMode: String = "ALL",
    val ntvCxResolveProbeOk: Boolean = false,
)

@Serializable
data class ProviderStats(
    val daddylive: Int = 0,
    val moveOnJoy: Int = 0,
    val iptvOrg: Int = 0,
    val sports: Int = 0,
    val ntvCx: Int = 0,
    val adult: Int = 0,
    val total: Int = 0,
)

@Serializable
data class CategoryCount(
    val groupTitle: String,
    val count: Int,
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
    val supplementEnabled: Boolean = false,
    val supplementChannels: Int = 0,
    val supplement: SupplementStatus? = null,
    val providers: ProviderStats? = null,
    val topCategories: List<CategoryCount> = emptyList(),
    val healing: HealingStatus? = null,
)

@Serializable
data class TivimateSetup(
    val playlist: String,
    val epg: String,
    val health: String,
    val hint: String,
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
)
