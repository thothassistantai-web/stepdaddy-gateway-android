package com.nova.stepdaddylivehd.gateway.upstream

object GatewayConfig {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
    const val TIVIMATE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val RESPORTZ_STREAM_TEMPLATE = "https://resportz.cfd/live/stream-%s.php"
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
    const val DEAD_MIRROR_TTL_MS = 300_000L
    const val UPSTREAM_CONNECT_TIMEOUT_SEC = 8L
    const val UPSTREAM_READ_TIMEOUT_SEC = 20L
    const val UPSTREAM_WRITE_TIMEOUT_SEC = 20L
    const val UPSTREAM_CALL_TIMEOUT_SEC = 22L
    val PREWARM_CHANNEL_IDS = listOf("857", "51", "360")

    val DADDYLIVE_HOSTS = setOf("daddylive.org", "daddylive.li", "daddylive.eu")
}
