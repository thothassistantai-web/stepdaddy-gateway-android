package com.thothassistant.stepdaddy.gateway.upstream

object SupplementConfig {
    const val MAX_CHANNELS = 100

    const val MAX_SPORTS_EVENTS = 12

    const val SYNC_INTERVAL_MS = 6 * 3600_000L

    const val DOWNLOAD_TIMEOUT_MS = 45_000L

    const val MAX_M3U_BYTES = 8 * 1024 * 1024

    const val MAX_EPG_BYTES = 32 * 1024 * 1024

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"

    const val GROUP_PREFIX = "📡 | Extra"

    const val SPORTS_GROUP_TITLE = "🏈 | Sports | TheTvApp"

    const val MOVEONJOY_REFERER = "https://moveonjoy.com/"

    fun playlistUrl(base: String): String = "${base.trimEnd('/')}/playlist.m3u8"

    fun epgGzipUrl(base: String): String = "${base.trimEnd('/')}/xmltv.xml.gz"

    fun epgXmlUrl(base: String): String = "${base.trimEnd('/')}/xmltv.xml"
}
