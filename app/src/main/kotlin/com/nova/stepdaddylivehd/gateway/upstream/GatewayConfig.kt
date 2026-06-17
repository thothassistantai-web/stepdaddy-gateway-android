package com.nova.stepdaddylivehd.gateway.upstream

object GatewayConfig {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
    const val TIVIMATE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val RESPORTZ_STREAM_TEMPLATE = "https://resportz.cfd/live/stream-%s.php"
    /** dlhd.pk relay paths used by daddylive embed when resportz.cfd is unreachable. */
    val DLHD_PK_STREAM_PATHS = listOf("watch", "cast", "plus", "player", "casting")
    const val CHANNEL_REFRESH_INTERVAL_MS = 600_000L
    const val STREAM_CACHE_TTL_MS = 30_000L
    const val UPSTREAM_CACHE_TTL_MS = 120_000L
    const val UPSTREAM_STALE_TTL_MS = 600_000L
    const val STALE_STREAM_TTL_MS = 600_000L
    /** Total budget for one stream resolve (all mirrors). */
    const val STREAM_FETCH_TIMEOUT_MS = 45_000L
    /** Per-mirror attempt — matches Python STREAM_MIRROR_ATTEMPT_TIMEOUT_SECONDS (18). */
    const val MIRROR_ATTEMPT_TIMEOUT_MS = 18_000L
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
    const val UPSTREAM_CONNECT_TIMEOUT_SEC = 8L
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

    val DADDYLIVE_HOSTS = setOf("daddylive.org", "daddylive.li", "daddylive.eu")
}
