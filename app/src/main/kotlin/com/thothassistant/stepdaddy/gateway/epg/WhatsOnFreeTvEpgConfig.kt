package com.thothassistant.stepdaddy.gateway.epg

/**
 * WhatsOnFreeTV publishes daily FAST-channel EPG as JSON on GitHub (TVmaze-sourced).
 * Site: https://whatsonfreetv.com — data repo: whatsonfreetv/whatsonfreetv-data
 */
object WhatsOnFreeTvEpgConfig {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"

    const val DATA_REPO = "whatsonfreetv/whatsonfreetv-data"
    const val DATA_BRANCH = "main"

    val EPG_FILES: List<Pair<String, String>> = listOf(
        "US" to "epg-us.json",
        "CA" to "epg-ca.json",
    )

    fun rawUrl(filename: String): String =
        "https://raw.githubusercontent.com/$DATA_REPO/$DATA_BRANCH/$filename"

    fun cdnUrls(filename: String): List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/$DATA_REPO@$DATA_BRANCH/$filename",
        "https://fastly.jsdelivr.net/gh/$DATA_REPO@$DATA_BRANCH/$filename",
        rawUrl(filename),
    )

    /** Skip TVmaze rows with no real title. */
    const val PLACEHOLDER_TITLE = "program information currently unavailable"

    /** Disk cache TTL — source updates ~daily. */
    const val CACHE_TTL_MS = 6 * 3600_000L

    /** Max JSON bytes per country file (epg-us ~21 MB). */
    const val MAX_BYTES = 28 * 1024 * 1024L

    const val CONNECT_TIMEOUT_MS = 12_000L
    const val READ_TIMEOUT_MS = 45_000L
    const val CALL_TIMEOUT_MS = 50_000L

    /** Minimum scored name match to accept a WOFTV channel key (audit actionable floor). */
    const val MIN_LOOKUP_SCORE = 0.55f

    /**
     * Playlist display-name norms → WOFTV catalog keys where exact normalize fails.
     * Keys/values use [EpgChannelMapper.normalizeName] form.
     */
    val NAME_ALIASES: Map<String, String> = mapOf(
        "stingray greatest holiday hits" to "stingray greatest hits",
        "pluto american true crime" to "pluto true crime",
        "pluto fantasy and horror" to "pluto horror",
        "pluto retro toons" to "pluto retro kid",
        "more crime" to "more true crime",
        "xite 90 s throwback" to "xite 90s throwback",
        "news12 plus new york" to "news 12 new york",
        "fox 5 new york ny" to "fox local new york",
        "fox 6 milwaukee wi" to "fox local milwaukee",
        "fox 5 washington dc" to "fox local washington dc",
        "tvs television network" to "samsung television network",
        "pocono television network" to "samsung television network",
        "ptl television network" to "samsung television network",
        "telemundo noticias ahora" to "noticias telemundo ahora",
        "nbc chicago news" to "nbc 5 chicago news",
        "gardening with monty don" to "garden with monty don",
        "wptv news west palm beach fl" to "wptv west palm beach 5",
        "nbc 26 green bay wi" to "nbc 26 wgba green bay",
        "int nbc 4 new york" to "nbc 4 new york news",
        "real housewives" to "real housewives vault",
    )
}
