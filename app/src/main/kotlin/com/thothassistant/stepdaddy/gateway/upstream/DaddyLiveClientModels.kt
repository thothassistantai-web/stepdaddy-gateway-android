package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest

internal data class CachedManifest(
    val savedAtMs: Long,
    val rewrittenPlaylist: String,
)

internal data class CachedUpstream(
    /** When the playlist body was last fetched (MEDIA-SEQUENCE freshness). */
    val savedAtMs: Long,
    val manifest: UpstreamManifest,
    /**
     * When the CDN master/media m3u8 URL binding was established via a full hub/embed resolve.
     * Survives short body TTLs so mid-play refreshes re-GET the m3u8 without re-hitting tiestep.
     */
    val boundAtMs: Long = savedAtMs,
)

data class HealingSnapshot(
    val lastAction: String,
    val recentActions: List<String>,
    val streamFailureCount: Int,
    val deadMirrorCount: Int,
    val streamCacheSize: Int,
    val upstreamCacheSize: Int,
    val staleDiskEntries: Int,
    val outageMode: Boolean,
    val cacheServeMode: Boolean,
    val breakerOpen: Boolean,
    val breakerRemainingMs: Long,
    val outageOpenCount: Int,
    val lastUpstreamSuccessMs: Long?,
    val canary: CanarySnapshot,
)

data class CanarySnapshot(
    val goodOk: Int = 0,
    val goodTotal: Int = 0,
    val badExpectedFail: Int = 0,
    val badTotal: Int = 0,
    val lastProbeMs: Long = 0L,
)

data class MirrorStatsSnapshot(
    val activeBaseUrl: String,
    val fastestMirrorEmaMs: Double?,
    val streamCacheHitRate: Double?,
    val mirrorLatenciesMs: Map<String, Double>,
)
