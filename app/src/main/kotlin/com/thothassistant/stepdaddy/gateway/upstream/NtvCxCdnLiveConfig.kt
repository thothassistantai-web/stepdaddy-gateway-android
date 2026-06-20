package com.thothassistant.stepdaddy.gateway.upstream

object NtvCxCdnLiveConfig {
    const val BASE_URL = "https://www.ntv.cx"

    const val CHANNELS_API = "$BASE_URL/api/get-channels"

    const val GROUP_TITLE = "📡 | Extra | 24/7"

    /** Max 24/7 rows merged from ntv.cx (cdnlive + hesgoales, ~950 total). */
    const val MAX_CHANNELS = 1000

    const val FETCH_TIMEOUT_MS = 65_000L

    const val MAX_CHANNELS_JSON_BYTES = 12 * 1024 * 1024

    const val REFERER = "https://cdnlivetv.tv/"

    const val ORIGIN = "https://cdnlivetv.tv"

    const val PLAYER_REFERER = "https://www.ntv.cx/"

    const val HESGOALES_REFERER = "https://hesgoaler.com/"

    const val HESGOALES_ORIGIN = "https://hesgoaler.com"

    const val PLAYER_USER = "ntvstream"

    const val PLAYER_PLAN = "free"
}
