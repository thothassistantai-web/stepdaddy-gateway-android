package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live HLS media playlists must not be cached across the CDN segment sliding window.
 * A prior 60s/120s TTL caused sticky `/vod-content` 502s (upstream 404) while
 * `/tivimate-stream` still returned 200 with a frozen MEDIA-SEQUENCE.
 */
class LivePlaylistCacheTtlTest {
    @Test
    fun streamCacheTtlStaysUnderSegmentWindow() {
        // Typical DaddyLive targetduration ≈ 4s; keep coalesce window << 3× that.
        assertTrue(GatewayConfig.STREAM_CACHE_TTL_MS <= 5_000L)
        assertTrue(GatewayConfig.UPSTREAM_CACHE_TTL_MS <= 5_000L)
        assertTrue(GatewayConfig.STALE_STREAM_TTL_MS <= 30_000L)
        assertTrue(GatewayConfig.UPSTREAM_STALE_TTL_MS <= 30_000L)
    }

    @Test
    fun outageGraceStillAllowsLongerStaleServe() {
        assertTrue(GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS >= 60_000L)
    }
}
