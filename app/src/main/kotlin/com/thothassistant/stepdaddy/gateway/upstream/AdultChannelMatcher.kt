package com.thothassistant.stepdaddy.gateway.upstream

/**
 * DaddyLive adult site channels (Pornhub, Brazzers, etc.) → [GroupTitleResolver.ADULT].
 * Upstream no longer uses the legacy `18+ (Player-XX)` naming; these names need explicit matching.
 */
object AdultChannelMatcher {
    private val EXACT_NAMES = setOf(
        "bangbros",
        "brazzers",
        "eporner",
        "naughtyamerica",
        "pornhd",
        "pornhub",
        "porntrex",
        "realitykings",
        "redtube",
        "spankbang",
        "tube8",
        "xhamster",
        "xnxx",
        "xvideos",
        "youporn",
    )

    private val NAME_SUBSTRINGS = listOf(
        "pornhub",
        "youporn",
        "xhamster",
        "xvideos",
        "xnxx",
        "redtube",
        "brazzers",
        "bangbros",
        "realitykings",
        "naughtyamerica",
        "spankbang",
        "eporner",
        "porntrex",
        "pornhd",
        "tube8",
        "playboy",
        "penthouse",
        "hustler",
        "hentai",
    )

    fun matches(channelName: String): Boolean {
        val norm = channelName.trim().lowercase()
            .replace(Regex(" \\[[^\\]]+\\]$"), "")
            .replace(Regex(" \\([^)]*\\)"), "")
            .replace(Regex("\\s+"), "")
        if (norm.isEmpty()) return false
        if (norm in EXACT_NAMES) return true
        val spaced = channelName.trim().lowercase()
        return NAME_SUBSTRINGS.any { spaced.contains(it) }
    }
}
