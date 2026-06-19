package com.thothassistant.stepdaddy.gateway.upstream

import java.text.Normalizer

/**
 * FiOS / Spectrum / DISH-style premium movie networks → [GroupTitleResolver.MOVIES].
 * Name-based so channels with only `#movies` (no `#premium`) still land in Movies.
 */
object PremiumMovieChannelMatcher {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val parentheticalRe = Regex(" \\([^)]*\\)")
    private val multiSpaceRe = Regex("\\s+")

    private val COUNTRY_SUFFIXES = listOf(
        " United States",
        " USA",
        " UK",
        " Canada",
        " CA",
        " West",
    ).sortedByDescending { it.length }

    /**
     * Longest prefixes first. Normalized name must equal or start with `prefix `.
     */
    private val NAME_PREFIXES = listOf(
        "starz encore westerns",
        "starz encore suspense",
        "starz encore classic",
        "starz encore family",
        "starz encore black",
        "starz encore action",
        "starz encore",
        "starz kids & family",
        "starz kids and family",
        "starz in black",
        "starz cinema",
        "starz comedy",
        "starz edge",
        "starz west",
        "the movie channel xtra",
        "the movie channel extra",
        "the movie channel",
        "showtime family zone",
        "showtime showcase",
        "showtime extreme",
        "showtime beyond",
        "showtime women",
        "showtime next",
        "showtime 2",
        "showtime west",
        "mgm+ drive-in",
        "mgm+ marquee",
        "mgm+ hits",
        "mgm+ epix",
        "mgm epix",
        "mgm+",
        "great american faith & living",
        "great american faith and living",
        "family movie classics",
        "hdnet movies",
        "sony movies",
        "5starmax",
        "outermax",
        "moviemax",
        "thrillermax",
        "actionmax",
        "moremax",
        "cinemax west",
        "cinemax",
        "hbo signature",
        "hbo comedy",
        "hbo family",
        "hbo latino",
        "hbo zone",
        "hbo west",
        "hbo2 west",
        "hbo2",
        "showtime",
        "starz",
        "hbo",
        "flix",
        "reelz",
        "indieplex",
        "retroplex",
        "movieplex",
        "cineplex",
        "fmc",
        "family movies",
        "encore",
    )

    fun matches(channelName: String): Boolean {
        val norm = normalize(channelName)
        if (norm.isEmpty()) return false
        if (isExcluded(norm)) return false
        return NAME_PREFIXES.any { prefix -> norm == prefix || norm.startsWith("$prefix ") }
    }

    fun normalize(channelName: String): String {
        var name = categorySuffixRe.replace(channelName.trim(), "").trim()
        name = parentheticalRe.replace(name, "").trim()
        while (true) {
            var stripped = false
            for (suffix in COUNTRY_SUFFIXES) {
                if (name.endsWith(suffix, ignoreCase = true)) {
                    name = name.dropLast(suffix.length).trim()
                    stripped = true
                    break
                }
            }
            if (!stripped) break
        }
        name = name.replace("/", " ")
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return multiSpaceRe.replace(ascii.lowercase(), " ").trim()
    }

    private fun isExcluded(norm: String): Boolean =
        norm.startsWith("starzplay") ||
            norm.startsWith("hbomax") ||
            norm.startsWith("hbo max")
}
