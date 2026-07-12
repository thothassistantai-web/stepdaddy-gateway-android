package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest

internal data class CachedManifest(
    val savedAtMs: Long,
    val rewrittenPlaylist: String,
)

internal data class CachedUpstream(
    val savedAtMs: Long,
    val manifest: UpstreamManifest,
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
