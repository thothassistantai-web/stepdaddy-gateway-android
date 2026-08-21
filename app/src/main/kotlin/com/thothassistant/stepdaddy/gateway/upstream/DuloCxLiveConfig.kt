package com.thothassistant.stepdaddy.gateway.upstream

/**
 * dulo.cx / dulo.gd Live TV catalog (public JSON) + play-time HLS via `/live-gateway/`.
 *
 * Catalog: `GET /api/live-tv/channels` (no auth). Playback sessions require a Supabase JWT
 * from a dulo Live TV account; optional bearer is stored in [GatewayEnvironment].
 *
 * Distinct from ntv.cx (different host, auth model, and CDN).
 *
 * @see docs/FMHY-STREAMING-EVAL.md
 */
object DuloCxLiveConfig {
    const val PROVIDER_TAG = "Dulo"
    const val ID_PREFIX = "dulo:"

    const val SITE_ORIGIN = "https://dulo.cx"
    const val API_BASE = "https://dulo.cx/api"
    const val CHANNELS_URL = "$API_BASE/live-tv/channels"
    const val SESSION_URL = "$API_BASE/session"
    const val PLAYBACK_SESSION_URL = "$API_BASE/live-tv/playback-session"
    const val ACTIVATE_DEVICE_URL = "$API_BASE/live-tv/activate-device"

    const val REFERER = "https://dulo.cx/live"
    const val ORIGIN = SITE_ORIGIN

    const val GROUP_TITLE = "📡 | Extra | Dulo Live"

    /** Soft cap — public catalog is ~200+; keep Fire Stick sync lean. */
    const val MAX_CHANNELS = 100

    const val MAX_CHANNELS_JSON_BYTES = 2 * 1024 * 1024

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Prefer USA sports/news slate that overlaps DaddyLive for consolidate fallbacks. */
    val CATEGORY_PRIORITY: Map<String, Int> = mapOf(
        "sports" to 100,
        "news" to 80,
        "entertainment" to 50,
        "documentary" to 30,
        "movies" to 20,
        "kids" to 10,
    )

    fun categoryTag(category: String?): String = when (category?.trim()?.lowercase()) {
        "sports" -> "#sports"
        "news" -> "#news"
        "entertainment" -> "#entertainment"
        "documentary" -> "#documentary"
        "movies" -> "#movies"
        "kids" -> "#kids"
        else -> "#live"
    }

    fun regionTagFromName(name: String): String {
        val upper = name.uppercase()
        return when {
            upper.contains("| USA") || upper.endsWith(" USA") -> "#us"
            upper.contains("| UK") || upper.endsWith(" UK") -> "#uk"
            upper.contains("| CA") || upper.endsWith(" CA") -> "#ca"
            upper.contains("| AU") -> "#au"
            upper.contains("| LAT") || upper.contains("| MEX") -> "#latam"
            upper.contains("| ES") || upper.contains("| PT") -> "#eu"
            else -> "#international"
        }
    }
}
