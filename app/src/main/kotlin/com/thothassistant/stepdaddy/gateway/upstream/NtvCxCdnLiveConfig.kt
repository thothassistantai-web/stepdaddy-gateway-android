package com.thothassistant.stepdaddy.gateway.upstream

object NtvCxCdnLiveConfig {
    const val BASE_URL = "https://www.ntv.cx"

    const val CHANNELS_API = "$BASE_URL/api/get-channels"

    const val GROUP_TITLE = "📡 | Extra | 24/7"

    /** Max 24/7 rows merged from ntv.cx (cdnlive + hesgoales, ~950 total). */
    const val MAX_CHANNELS = 1000

    const val FETCH_TIMEOUT_MS = 65_000L

    /** ntv.cx cold-cache API can exceed 65s on slow links; site allows ~65s + retries. */
    const val CATALOG_FETCH_TIMEOUT_MS = 120_000L

    const val CATALOG_FETCH_RETRIES = 3

    const val MAX_CHANNELS_JSON_BYTES = 12 * 1024 * 1024

    const val REFERER = "https://cdnlivetv.tv/"

    const val ORIGIN = "https://cdnlivetv.tv"

    const val PLAYER_REFERER = "https://www.ntv.cx/"

    const val HESGOALES_REFERER = "https://hesgoaler.com/"

    const val HESGOALES_ORIGIN = "https://hesgoaler.com"

    const val CATALOG_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val PLAYER_PLAN = "free"
}
