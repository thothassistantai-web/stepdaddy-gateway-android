package com.thothassistant.stepdaddy.gateway.upstream

object NtvCxCdnLiveConfig {
    const val BASE_URL = "https://www.ntv.cx"

    const val CHANNELS_API = "$BASE_URL/api/get-channels"

    const val GROUP_TITLE = "📡 | Extra | CDN Live"

    /** Max CDN Live rows merged from ntv.cx (upstream advertises ~450). */
    const val MAX_CHANNELS = 450

    const val FETCH_TIMEOUT_MS = 65_000L

    const val MAX_CHANNELS_JSON_BYTES = 12 * 1024 * 1024

    const val REFERER = "https://cdnlivetv.tv/"

    const val ORIGIN = "https://cdnlivetv.tv"

    const val PLAYER_REFERER = "https://www.ntv.cx/"

    const val PLAYER_USER = "ntvstream"

    const val PLAYER_PLAN = "free"
}
