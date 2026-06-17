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
    /** Matches Python STREAM_FETCH_TIMEOUT_SECONDS default (60). */
    const val STREAM_FETCH_TIMEOUT_MS = 60_000L
    const val MIRROR_ATTEMPT_TIMEOUT_MS = STREAM_FETCH_TIMEOUT_MS
    const val DEAD_MIRROR_TTL_MS = 300_000L
    const val UPSTREAM_CONNECT_TIMEOUT_SEC = 10L
    const val UPSTREAM_READ_TIMEOUT_SEC = 60L
    const val UPSTREAM_WRITE_TIMEOUT_SEC = 60L
    const val UPSTREAM_CALL_TIMEOUT_SEC = 65L

    val DADDYLIVE_HOSTS = setOf("daddylive.org", "daddylive.li", "daddylive.eu")
}
