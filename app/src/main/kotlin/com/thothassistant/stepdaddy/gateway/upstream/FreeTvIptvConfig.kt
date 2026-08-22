package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Free-TV/IPTV curated country playlists (public HLS / free-to-air style feeds).
 * Complementary live backup to iptv-org FAST playlists.
 *
 * @see <a href="https://github.com/Free-TV/IPTV">Free-TV/IPTV</a>
 * @see docs/FMHY-STREAMING-EVAL.md
 */
object FreeTvIptvConfig {
    const val PROVIDER_TAG = "Free-TV"
    const val ID_PREFIX = "freetv:"
    const val REFERER = "https://github.com/Free-TV/IPTV"
    const val ORIGIN = "https://github.com"

    const val RAW_BASE_URL =
        "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/"

    /** Same bytes via jsDelivr when raw.githubusercontent.com stalls on phone LTE. */
    val CDN_BASE_URLS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/Free-TV/IPTV@master/playlists/",
        "https://fastly.jsdelivr.net/gh/Free-TV/IPTV@master/playlists/",
        "https://gcore.jsdelivr.net/gh/Free-TV/IPTV@master/playlists/",
    )

    /** High-value English-language backups; keep small for Fire Stick sync time. */
    val PLAYLIST_FILES: List<String> = listOf(
        "playlist_usa.m3u8",
        "playlist_canada.m3u8",
        "playlist_uk.m3u8",
    )

    const val MAX_CHANNELS_AFTER_DEDUP = 200
    const val MAX_BYTES_PER_PLAYLIST = 512 * 1024

    fun rawUrl(filename: String): String = RAW_BASE_URL + filename

    fun candidateUrls(filename: String): List<String> =
        (CDN_BASE_URLS + listOf(RAW_BASE_URL)).map { it + filename }

    fun countryTagFor(filename: String): String = when {
        filename.contains("usa", ignoreCase = true) -> "#us"
        filename.contains("canada", ignoreCase = true) -> "#ca"
        filename.contains("uk", ignoreCase = true) -> "#uk"
        else -> "#international"
    }

    fun isPlayableHttpStream(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if ("youtube.com" in u || "youtu.be" in u || "twitch.tv" in u) return false
        return true
    }
}
