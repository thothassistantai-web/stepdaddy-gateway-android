package com.thothassistant.stepdaddy.gateway.sidecar

object SidecarConfig {
    const val PORT = 4124

    const val LOOPBACK_BASE = "http://127.0.0.1:$PORT"

    /** TVApp2 pre-built M3U (same source as full Node sidecar). */
    const val FORMATTED_PLAYLIST_URL =
        "https://epg.binaryninja.net/XMLTV-EPG/formatted_v2.0.0.dat"

    const val SYNC_INTERVAL_MS = 6 * 3600_000L

    const val DOWNLOAD_TIMEOUT_MS = 60_000L

    const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
}
