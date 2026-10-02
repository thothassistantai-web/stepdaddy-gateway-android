package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.diagnostics.RuntimeTuneRuntime
import com.thothassistant.stepdaddy.gateway.relay.DomainRelayRuntime

object GatewayConfig {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
    const val TIVIMATE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    /**
     * Legacy resportz hosts — disabled: resportz.cfd presents cert for 1ststreams.cfd (hostname
     * mismatch), resportz.live is NXDOMAIN. Burning these as last-resort stalls the resolve budget
     * and surfaces as mid-play HttpDataSource / looping on sticks.
     */
    val RESPORTZ_WATCH_HOSTS = emptyList<String>()
    const val RESPORTZ_STREAM_TEMPLATE = "https://resportz.cfd/live/stream-%s.php"
    const val RESPORTZ_STREAM_PATH = "/live/stream-%s.php"
    private val DEFAULT_DLHD_RELAY_HOSTS = listOf(
        "https://daddylive.li",
        "https://dlstreams.st",
        "https://dlhd.st",
        "https://dlhd.pk",
    )
    /** Active dlhd relay hosts (prefer daddylive.li; legacy dlstreams paths as fallback). */
    val DLHD_RELAY_HOSTS: List<String>
        get() = DomainRelayRuntime.relayHosts ?: DEFAULT_DLHD_RELAY_HOSTS
    private val DEFAULT_DLHD_EMBED_HOSTS = listOf(
        "https://daddylive.li",
        "https://daddylive.at",
        "https://dlstreams.st",
        "https://dlhd.st",
        "https://dlhd.pk",
        "https://dlhd.li",
        "https://dlhd.org",
    )
    /** dlhd embed hosts used for direct m3u8 fetches with embed referer. */
    val DLHD_EMBED_HOSTS: List<String>
        get() = DomainRelayRuntime.embedHosts ?: DEFAULT_DLHD_EMBED_HOSTS
    /**
     * Relay paths — prefer `plus` (JWPlayer XOR embeds), then `watch` (daddyplayer direct m3u8),
     * before cast/player stubs that 403 or hotlink-block.
     */
    val DLHD_PK_STREAM_PATHS = listOf("plus", "watch", "cast", "casting", "player")
    /** Wizard/setup M3U cap — full catalog (~5k ch) blocks FUSA for minutes; bootstrap must be fast. */
    const val SETUP_BOOTSTRAP_MAX_CHANNELS = 50
    const val CHANNEL_REFRESH_INTERVAL_MS = 600_000L
    /**
     * Fresh rewritten live media playlist TTL. Must stay well under the CDN segment
     * sliding window (~targetduration × 2–3). A 60s cache previously kept serving
     * rolled-off `.ts` URLs → `/vod-content` HTTP 404 → sticky client 502s while the
     * manifest route still returned 200 `#EXTM3U`.
     */
    const val DEFAULT_STREAM_CACHE_TTL_MS = 2_500L
    val STREAM_CACHE_TTL_MS: Long
        get() = RuntimeTuneRuntime.effective().streamCacheTtlMs
    /**
     * Live sliding-window media playlists (#EXT-X-MEDIA-SEQUENCE, no ENDLIST) must refresh
     * every few seconds. Caching them for STREAM/UPSTREAM TTL freezes MEDIA-SEQUENCE
     * while the window is only ~4×6s ≈ 24s → TiviMate visually loops every ~20s.
     */
    const val DEFAULT_LIVE_PLAYLIST_CACHE_TTL_MS = 2_500L
    val LIVE_PLAYLIST_CACHE_TTL_MS: Long
        get() = RuntimeTuneRuntime.effective().livePlaylistCacheTtlMs
    /**
     * Fresh upstream playlist *body* TTL. After this, re-GET [UpstreamManifest.masterUrl]
     * instead of re-walking hubs (see [UPSTREAM_MASTER_BIND_TTL_MS]).
     */
    const val DEFAULT_UPSTREAM_CACHE_TTL_MS = 2_500L
    val UPSTREAM_CACHE_TTL_MS: Long
        get() = RuntimeTuneRuntime.effective().upstreamCacheTtlMs
    /**
     * How long a resolved CDN m3u8 URL binding stays valid for cheap mid-play refreshes.
     * Avoids hammering tiestep/assetrage (HTTP 429) every playlist poll.
     */
    const val UPSTREAM_MASTER_BIND_TTL_MS = 1_800_000L
    /**
     * Stale-while-revalidate window for rewritten playlists. On body-TTL miss, serve the
     * last-good playlist immediately and refresh CDN m3u8 in the background so TiviMate
     * never blocks on a hub walk (3.0.55 hitch: 3–8s playlist latency).
     */
    const val LIVE_SOFT_SERVE_MS = 8_000L
    /** Soft stale-good window while mirrors are healthy (live segments expire quickly). */
    const val UPSTREAM_STALE_TTL_MS = 15_000L
    const val STALE_STREAM_TTL_MS = 15_000L
    /** Total budget for one stream resolve (all mirrors). */
    const val DEFAULT_STREAM_FETCH_TIMEOUT_MS = 28_000L
    val STREAM_FETCH_TIMEOUT_MS: Long
        get() = RuntimeTuneRuntime.effective().streamFetchTimeoutMs
    /**
     * Per-mirror attempt wall clock. Hub pages use [HUB_PAGE_TIMEOUT_MS]; after `_econfig`
     * the m3u8 fetch is reserved via [M3U8_FETCH_TIMEOUT_MS] (NonCancellable) so hub burn
     * does not cancel the winning stream URL.
     */
    const val DEFAULT_MIRROR_ATTEMPT_TIMEOUT_MS = 10_000L
    val MIRROR_ATTEMPT_TIMEOUT_MS: Long
        get() = RuntimeTuneRuntime.effective().mirrorAttemptTimeoutMs
    /** Per-hub / nested embed HTML fetch — fail fast on slow/dead daddy-url stubs. */
    const val HUB_PAGE_TIMEOUT_MS = 2_000L
    /** Reserved budget to fetch the real m3u8 after `_econfig` / atob extraction. */
    const val M3U8_FETCH_TIMEOUT_MS = 3_500L
    /** Skip deprioritized hubs (nontongo / rippleplays / …) when remaining budget is low. */
    const val DEPRIORITIZED_HUB_MIN_REMAINING_MS = 3_000L
    /** Multi-hour TTL for channelId → winning embed/tiestep URL cache. */
    const val WINNING_EMBED_CACHE_TTL_MS = 6L * 60L * 60L * 1_000L
    /**
     * Parallel dlhd plus/cast race budget. Must cover watch HTML + embed + CDN m3u8 fetch.
     */
    const val DEFAULT_DLHD_RACE_TIMEOUT_MS = 18_000L
    val DLHD_RACE_TIMEOUT_MS: Long
        get() = RuntimeTuneRuntime.effective().dlhdRaceTimeoutMs
    /** Cap parallel stream resolves — keep modest so iptv CDN + TiviMate do not wedge LTE. */
    const val DEFAULT_UPSTREAM_FETCH_MAX_CONCURRENT = 2
    val UPSTREAM_FETCH_MAX_CONCURRENT: Int
        get() = RuntimeTuneRuntime.effective().upstreamFetchMaxConcurrent
    /**
     * Cap concurrent `/content` + `/vod-content` binary segment proxies.
     * Each call buffers a full segment (often 1–2 MB); unbounded concurrency OOMs ONN (~1.4 GiB)
     * and stalls the CIO loop → segment time ≫ #EXTINF → visual loop / HttpDataSource.
     */
    const val DEFAULT_CONTENT_PROXY_MAX_CONCURRENT = 3
    val CONTENT_PROXY_MAX_CONCURRENT: Int
        get() = RuntimeTuneRuntime.effective().contentProxyMaxConcurrent
    /**
     * Max wait for a content-proxy slot before 503 (fail fast so ExoPlayer retries).
     * Keep well under typical #EXTINF (~6s) so a saturated proxy does not freeze the picture
     * for 12–20s before the player can soft-retry.
     */
    const val DEFAULT_CONTENT_PROXY_WAIT_MS = 4_000L
    val CONTENT_PROXY_WAIT_MS: Long
        get() = RuntimeTuneRuntime.effective().contentProxyWaitMs
    /**
     * Hard ceiling for one `/content`|`/vod-content` upstream segment fetch while holding a
     * proxy slot. Must be ≪ OkHttp read/call timeouts (25–35s) or hung CDNs pin all slots
     * (`proxyInFlight` stuck at cap → 503 storms → frozen TiviMate frame).
     */
    const val CONTENT_PROXY_SEGMENT_TIMEOUT_MS = 10_000L
    /** Max wait for a fetch slot when TiviMate requests several channels at once. */
    const val DEFAULT_UPSTREAM_FETCH_WAIT_MS = 18_000L
    val UPSTREAM_FETCH_WAIT_MS: Long
        get() = RuntimeTuneRuntime.effective().upstreamFetchWaitMs
    const val DEAD_MIRROR_TTL_MS = 300_000L
    const val MIRROR_FAILURE_BACKOFF_BASE_MS = 10_000L
    const val MIRROR_FAILURE_BACKOFF_MAX_MS = 180_000L
    const val OUTAGE_BREAKER_BASE_MS = 30_000L
    const val OUTAGE_BREAKER_MAX_MS = 300_000L
    const val OUTAGE_STALE_GRACE_TTL_MS = 1_800_000L
    const val STALE_DISK_MAX_ENTRIES = 64
    const val STALE_DISK_TTL_MS = 1_800_000L
    const val OUTAGE_MIRROR_ATTEMPT_TIMEOUT_MS = 7_000L
    const val OUTAGE_STREAM_FETCH_TIMEOUT_MS = 12_000L
    const val OUTAGE_PROBE_TIMEOUT_MS = 8_000L
    const val INVALIDATE_COOLDOWN_MS = 180_000L
    const val CHANNEL_MIRROR_COOLDOWN_BASE_MS = 20_000L
    const val CHANNEL_MIRROR_COOLDOWN_MAX_MS = 180_000L
    const val UPSTREAM_CONNECT_TIMEOUT_SEC = 8L
    const val UPSTREAM_READ_TIMEOUT_SEC = 25L
    const val UPSTREAM_WRITE_TIMEOUT_SEC = 20L
    const val UPSTREAM_CALL_TIMEOUT_SEC = 35L
    val PREWARM_CHANNEL_IDS = listOf("857", "51", "360")
    val WATCHDOG_PROBE_CHANNEL_IDS = listOf("51", "857")
    /** Known-good channels for outage canary probes and poison-cascade ordering tests. */
    val CANARY_GOOD_CHANNEL_IDS = listOf("51", "857", "360")
    /** Channel id that should fail with a channel-specific error, not mirror poisoning. */
    val CANARY_BAD_CHANNEL_IDS = listOf("999999")
    const val WATCHDOG_INTERVAL_MS = 120_000L
    const val WATCHDOG_INITIAL_DELAY_MS = 30_000L
    const val WATCHDOG_PROBE_TIMEOUT_MS = 25_000L
    const val WATCHDOG_RESTART_THRESHOLD = 3
    const val STREAM_FAILURE_INVALIDATE_THRESHOLD = 2
    const val HEALING_LOG_MAX = 20

    private val DEFAULT_DADDYLIVE_HOSTS = setOf(
        "daddylive.org",
        "daddylive.li",
        "daddylive.at",
        "dlstreams.st",
        "dlhd.st",
        "dlhd.pk",
        "dlhd.li",
        "dlhd.org",
    )
    val DADDYLIVE_HOSTS: Set<String>
        get() {
            val relay = DomainRelayRuntime
            val extra = buildSet {
                relay.primary?.let { hostFromUrl(it)?.let { h -> add(h) } }
                relay.mirrors?.forEach { hostFromUrl(it)?.let { h -> add(h) } }
                relay.relayHosts?.forEach { hostFromUrl(it)?.let { h -> add(h) } }
                relay.embedHosts?.forEach { hostFromUrl(it)?.let { h -> add(h) } }
            }
            return if (extra.isEmpty()) DEFAULT_DADDYLIVE_HOSTS else DEFAULT_DADDYLIVE_HOSTS + extra
        }
    private val ALWAYS_BLOCKED_DADDYLIVE_HOSTS = setOf(
        "daddylive.org",
        /** NXDOMAIN / DNS-dead — hedged race previously double-downloaded hub HTML against it. */
        "daddylive.eu",
    )
    /** Mirrors excluded from automatic rotation (seized, deprecated, or structurally broken). */
    val DADDYLIVE_BLOCKED_HOSTS: Set<String>
        get() = ALWAYS_BLOCKED_DADDYLIVE_HOSTS + (DomainRelayRuntime.blockedHosts ?: emptySet())

    private fun hostFromUrl(baseUrl: String): String? =
        runCatching {
            java.net.URL(baseUrl.trimEnd('/')).host.lowercase().takeIf { it.isNotBlank() }
        }.getOrNull()
    /** EMA weight for mirror/path latency samples (higher = more reactive). */
    const val MIRROR_LATENCY_EMA_ALPHA = 0.35
    /** Sort rank for mirrors with no latency history yet. */
    const val MIRROR_UNKNOWN_LATENCY_MS = 30_000.0
    /** Penalty sample applied when a mirror/path attempt fails. */
    const val MIRROR_FAILURE_PENALTY_MS = 120_000L
    /**
     * Concurrent dlhd relay URLs to race within one mirror attempt.
     * Prefer path diversity (plus+watch) over 3× same-path hosts — Local Channels often have
     * dead plus CDNs while watch/daddyliveplayer still works; 3× ~640KB HTML races LMK ONN.
     */
    const val DEFAULT_DLHD_PK_PARALLEL_PROBE_COUNT = 2
    val DLHD_PK_PARALLEL_PROBE_COUNT: Int
        get() = RuntimeTuneRuntime.effective().dlhdPkParallelProbeCount
    /**
     * Cap upstream HTML/m3u8 text bodies. Watch pages are ~640–660KB with iframe near the end;
     * anything multi-MB is a runaway download that LMKs the 256MB heap.
     */
    const val MAX_UPSTREAM_TEXT_BYTES = 768_000L
    /** Soft cap for leaf m3u8 playlists (master/media). */
    const val MAX_M3U8_TEXT_BYTES = 256_000L
    /** Default for non-stream getText (catalogs, relay JSON, update manifests). */
    const val DEFAULT_GET_TEXT_MAX_BYTES = 8_000_000L
    /** Max serial dlhd watch attempts after the parallel race (fail fast on dead Local embeds). */
    const val DLHD_SERIAL_MAX_ATTEMPTS = 4
    const val DLHD_PATH_COOLDOWN_BASE_MS = 10_000L
    const val DLHD_PATH_COOLDOWN_MAX_MS = 120_000L
    const val RESPORTZ_HOST_COOLDOWN_BASE_MS = 12_000L
    const val RESPORTZ_HOST_COOLDOWN_MAX_MS = 120_000L
    const val DLHD_HOST_COOLDOWN_BASE_MS = 12_000L
    const val DLHD_HOST_COOLDOWN_MAX_MS = 120_000L
    /** Hedged mirror race: max wait for the first successful mirror. */
    const val HEDGED_MIRROR_RACE_TIMEOUT_MS = 6_000L
    const val DEFAULT_HEDGED_MIRROR_RACE_ENABLED = true
    val HEDGED_MIRROR_RACE_ENABLED: Boolean
        get() = RuntimeTuneRuntime.effective().hedgedMirrorRaceEnabled
    val XAMELEON_HOSTS = setOf("xameleon")
}
