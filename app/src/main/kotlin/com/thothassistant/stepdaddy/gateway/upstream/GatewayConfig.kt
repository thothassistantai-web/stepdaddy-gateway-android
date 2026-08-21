package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.relay.DomainRelayRuntime

object GatewayConfig {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
    const val TIVIMATE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val RESPORTZ_STREAM_TEMPLATE = "https://resportz.cfd/live/stream-%s.php"
    const val RESPORTZ_STREAM_PATH = "/live/stream-%s.php"
    /** Legacy resportz hosts — kept as last-resort; most installs use dlstreams relay paths. */
    val RESPORTZ_WATCH_HOSTS = listOf(
        "https://resportz.cfd",
        "https://resportz.live",
    )
    private val DEFAULT_DLHD_RELAY_HOSTS = listOf(
        "https://dlstreams.st",
        "https://dlhd.st",
        "https://dlhd.pk",
    )
    /** Active dlhd relay hosts (dlhd.pk/st redirect to dlstreams.st as of 2026-08). */
    val DLHD_RELAY_HOSTS: List<String>
        get() = DomainRelayRuntime.relayHosts ?: DEFAULT_DLHD_RELAY_HOSTS
    private val DEFAULT_DLHD_EMBED_HOSTS = listOf(
        "https://dlstreams.st",
        "https://dlhd.st",
        "https://dlhd.pk",
        "https://dlhd.li",
        "https://dlhd.org",
        "https://daddylive.li",
        "https://daddylive.eu",
        "https://daddylive.at",
    )
    /** dlhd embed hosts used for direct m3u8 fetches with embed referer. */
    val DLHD_EMBED_HOSTS: List<String>
        get() = DomainRelayRuntime.embedHosts ?: DEFAULT_DLHD_EMBED_HOSTS
    /** Relay paths — player/casting first (watch/cast often 403/404 on current mirrors). */
    val DLHD_PK_STREAM_PATHS = listOf("player", "casting", "watch", "cast", "plus")    /** Wizard/setup M3U cap — full catalog (~5k ch) blocks FUSA for minutes; bootstrap must be fast. */
    const val SETUP_BOOTSTRAP_MAX_CHANNELS = 50
    const val CHANNEL_REFRESH_INTERVAL_MS = 600_000L
    const val STREAM_CACHE_TTL_MS = 60_000L
    const val UPSTREAM_CACHE_TTL_MS = 120_000L
    const val UPSTREAM_STALE_TTL_MS = 600_000L
    const val STALE_STREAM_TTL_MS = 600_000L
    /** Total budget for one stream resolve (all mirrors). */
    const val STREAM_FETCH_TIMEOUT_MS = 45_000L
    /** Per-mirror attempt — fail fast on dead mirrors, then rotate. */
    const val MIRROR_ATTEMPT_TIMEOUT_MS = 12_000L
    const val UPSTREAM_FETCH_MAX_CONCURRENT = 2
    /** Max wait for a fetch slot when TiviMate requests several channels at once. */
    const val UPSTREAM_FETCH_WAIT_MS = 20_000L
    const val DEAD_MIRROR_TTL_MS = 300_000L
    const val MIRROR_FAILURE_BACKOFF_BASE_MS = 10_000L
    const val MIRROR_FAILURE_BACKOFF_MAX_MS = 180_000L
    const val OUTAGE_BREAKER_BASE_MS = 30_000L
    const val OUTAGE_BREAKER_MAX_MS = 300_000L
    const val OUTAGE_STALE_GRACE_TTL_MS = 1_800_000L
    const val STALE_DISK_MAX_ENTRIES = 64
    const val STALE_DISK_TTL_MS = 1_800_000L
    const val OUTAGE_MIRROR_ATTEMPT_TIMEOUT_MS = 6_000L
    const val OUTAGE_STREAM_FETCH_TIMEOUT_MS = 12_000L
    const val OUTAGE_PROBE_TIMEOUT_MS = 8_000L
    const val INVALIDATE_COOLDOWN_MS = 180_000L
    const val CHANNEL_MIRROR_COOLDOWN_BASE_MS = 20_000L
    const val CHANNEL_MIRROR_COOLDOWN_MAX_MS = 180_000L
    const val UPSTREAM_CONNECT_TIMEOUT_SEC = 5L
    const val UPSTREAM_READ_TIMEOUT_SEC = 20L
    const val UPSTREAM_WRITE_TIMEOUT_SEC = 20L
    const val UPSTREAM_CALL_TIMEOUT_SEC = 22L
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
        "daddylive.eu",
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
    private val DEFAULT_DADDYLIVE_BLOCKED_HOSTS = setOf(
        "daddylive.org",
    )
    /** Mirrors excluded from automatic rotation (seized, deprecated, or structurally broken). */
    val DADDYLIVE_BLOCKED_HOSTS: Set<String>
        get() = DomainRelayRuntime.blockedHosts ?: DEFAULT_DADDYLIVE_BLOCKED_HOSTS

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
    /** Concurrent dlhd.pk relay paths to race within one mirror attempt. */
    const val DLHD_PK_PARALLEL_PROBE_COUNT = 3
    const val DLHD_PATH_COOLDOWN_BASE_MS = 10_000L
    const val DLHD_PATH_COOLDOWN_MAX_MS = 120_000L
    const val RESPORTZ_HOST_COOLDOWN_BASE_MS = 12_000L
    const val RESPORTZ_HOST_COOLDOWN_MAX_MS = 120_000L
    const val DLHD_HOST_COOLDOWN_BASE_MS = 12_000L
    const val DLHD_HOST_COOLDOWN_MAX_MS = 120_000L
    /** Hedged mirror race: max wait for the first successful mirror. */
    const val HEDGED_MIRROR_RACE_TIMEOUT_MS = 8_000L
    const val HEDGED_MIRROR_RACE_ENABLED = true
    val XAMELEON_HOSTS = setOf("xameleon")
}
