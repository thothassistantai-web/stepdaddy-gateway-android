package com.thothassistant.stepdaddy.gateway.epg

/**
 * Public tvtv.us grid API for US cable/OTA programme data (iptv-org lineup USA-NY71652-X).
 * Used as on-device gap-fill when epgshare leaves mapped Xtream-style ids empty.
 */
object TvtvUsEpgConfig {
    const val LINEUP_ID = "USA-NY71652-X"

    /**
     * Premium US cable feeds where epgshare `US2` ids use Pacific/west offsets
     * ([`.HD.(Pacific).us2`]) but DaddyLive/Xtream streams are Eastern.
     * Prefer tvtv.us East site_ids (see [tvtv_id_bridge.json]) before epgshare merge.
     */
    val EASTERN_PREFERRED_PLAYLIST_IDS: Set<String> = setOf(
        "HBO2.us",
        "Showtime.us",
        "StarzInBlack.us",
        "StarzKidsFamily.us",
    )

    const val GRID_URL_TEMPLATE =
        "https://www.tvtv.us/api/v1/lineup/$LINEUP_ID/grid/{start}/{end}/{site_id}"

    const val BRIDGE_ASSET = "tvtv_id_bridge.json"

    /** Re-download grid JSON after this age. */
    const val GRID_CACHE_TTL_MS = 3600_000L

    /** Max bytes per grid response. */
    const val MAX_GRID_BYTES = 4 * 1024 * 1024

    /** tvtv.us returns HTTP 400 when the grid window exceeds ~24 hours. */
    const val MAX_GRID_WINDOW_HOURS = 24L

    /** tvtv.us also rejects trailing partial windows shorter than 24 hours. */
    const val MIN_GRID_WINDOW_HOURS = 24L

    /** Cap grid fetches per build; tvtv.us rate-limits above ~20–30 rapid requests. */
    const val MAX_CHANNELS_PER_BUILD = 24

    /** Pause between grid HTTP calls to avoid 429 rate limits. */
    const val GRID_REQUEST_DELAY_MS = 2_000L

    /** Retries when tvtv.us returns HTTP 429 (rate limit). */
    const val MAX_GRID_429_RETRIES = 3

    /** Initial backoff after 429; doubles each retry. */
    const val GRID_429_BACKOFF_MS = 5_000L

    const val DOWNLOAD_TIMEOUT_MS = 45_000L

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    fun gridUrl(startIso: String, endIso: String, siteId: String): String =
        GRID_URL_TEMPLATE
            .replace("{start}", startIso)
            .replace("{end}", endIso)
            .replace("{site_id}", siteId)
}
