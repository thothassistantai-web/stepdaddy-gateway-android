package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest

internal data class CachedManifest(
    val savedAtMs: Long,
    val rewrittenPlaylist: String,
) {
    val isLiveSlidingWindow: Boolean
        get() = HlsPlaylistKind.isLiveSlidingWindow(rewrittenPlaylist)
}

internal data class CachedUpstream(
    /** When the playlist body was last fetched (MEDIA-SEQUENCE freshness). */
    val savedAtMs: Long,
    val manifest: UpstreamManifest,
    /**
     * When the CDN master/media m3u8 URL binding was established via a full hub/embed resolve.
     * Survives short body TTLs so mid-play refreshes re-GET the m3u8 without re-hitting tiestep.
     */
    val boundAtMs: Long = savedAtMs,
) {
    val isLiveSlidingWindow: Boolean
        get() = HlsPlaylistKind.isLiveSlidingWindow(manifest.playlistText)
}

/** Live vs VOD playlist classification for cache TTL. */
internal object HlsPlaylistKind {
    fun isLiveSlidingWindow(playlistText: String): Boolean {
        val text = playlistText
        if (!text.contains("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true)) return false
        if (text.contains("#EXT-X-ENDLIST", ignoreCase = true)) return false
        return true
    }

    fun cacheTtlMs(playlistText: String, nonLiveTtlMs: Long): Long =
        if (isLiveSlidingWindow(playlistText)) {
            GatewayConfig.LIVE_PLAYLIST_CACHE_TTL_MS
        } else {
            nonLiveTtlMs
        }
}

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
