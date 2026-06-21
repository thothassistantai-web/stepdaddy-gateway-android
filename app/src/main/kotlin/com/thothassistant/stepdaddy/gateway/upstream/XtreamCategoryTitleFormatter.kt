package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Category-first playlist titles with Xtream-style country prefix and quality suffix.
 * Groups stay on [GroupTitleResolver] categories; only display names change.
 */
object XtreamCategoryTitleFormatter {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val resolutionSuffixRe = Regex("""\s*\((?:1080p|720p|480p|360p|4k|uhd)\)""", RegexOption.IGNORE_CASE)
    private val geoBlockedRe = Regex("""\s*\[Geo-blocked\]$""", RegexOption.IGNORE_CASE)
    private val multiSpaceRe = Regex("\\s+")

    private val trailingCountryRe = Regex(
        """\s+(?:USA|US|UK|CA|AU|NZ|DE|FR|IT|ES|PL|GR|QA|IL|AE|RS|HR|BA|BG|ZA|DK|PT|MX|SE|CZ|NL|TR|BR|MY|RO|AR|CY|RU|IN|IE|PK|HU|EG|AT|BD|CL|UY|CO|INT|International)$""",
        RegexOption.IGNORE_CASE,
    )

    private val providerTagRe = Regex(
        """\s+(?:Samsung|Pluto|Roku|Plex|Tubi|Distro|Xumo|Stirr|LocalNow|BBC|CDN|MOJ|FAST|Peacock|Hulu|Netflix|Prime|Paramount\+?)$""",
        RegexOption.IGNORE_CASE,
    )

    private val flagSuffixRe = Regex("""\s+[🇺🇸🇬🇧🇨🇦🇦🇺🇳🇿🇩🇪🇫🇷🇮🇹🇪🇸🇵🇱🇬🇷🇶🇦🇮🇱🇦🇪🇷🇸🇭🇷🇧🇦🇧🇬🇿🇦🇩🇰🇵🇹🇲🇽🇸🇪🇨🇿🇳🇱🇹🇷🇧🇷🇲🇾🇷🇴🇦🇷🇨🇾🇷🇺🇮🇳🇮🇪🇵🇰🇭🇺🇪🇬🇦🇹🇧🇩🇨🇱🇺🇾🇨🇴🌐🌍🏴]\s*(?:US|UK|CA|INT)?$""")

    private val spellingFixes = mapOf(
        "Israe" to "Israel",
        "Espanol" to "Español",
        "espanol" to "español",
    )

    fun format(
        channelName: String,
        resolution: GroupTitleResolver.Resolution,
        source: PlaylistTitleSource,
    ): String {
        if (resolution.isAdult || source == PlaylistTitleSource.ADULT) {
            return formatAdult(channelName)
        }

        val hadFastResolution = resolutionSuffixRe.containsMatchIn(channelName)
        var core = sanitizeCore(channelName)
        core = applySpellingFixes(core)
        if (core.isEmpty()) return channelName.replace("\"", "'").trim()

        val countryPrefix = countryPrefix(resolution.countryCode)
        val quality = when {
            source == PlaylistTitleSource.ADULT_SWIM_247 -> " ᴿᴬᵂ"
            source == PlaylistTitleSource.SPECIAL_EVENT -> " ᴸᴵⱽᴱ"
            source == PlaylistTitleSource.FAST || hadFastResolution -> " ᴿᴬᵂ"
            source == PlaylistTitleSource.SIDECAR -> " ᴸᴵⱽᴱ"
            else -> " HD"
        }
        return "$countryPrefix: ${core.uppercase()}$quality".trim()
    }

    fun formatAdultSwimMarathon(channelName: String): String {
        val core = sanitizeCore(channelName)
        if (core.isEmpty()) return "US: 24/7 : Adultswim ${channelName.trim()} ᴿᴬᵂ"
        return "US: 24/7 : Adultswim ${core.uppercase()} ᴿᴬᵂ"
    }

    fun formatSpecialEvent(channelName: String, league: String?): String {
        val core = sanitizeCore(channelName)
        val leagueLabel = league?.trim()?.uppercase().orEmpty().ifBlank { "EVENT" }
        if (core.isEmpty()) return "US: $leagueLabel ${channelName.trim()} ᴸᴵⱽᴱ"
        return "US: $leagueLabel ${core.uppercase()} ᴸᴵⱽᴱ"
    }

    fun formatGuideSchedule(category: String, league: String?): String {
        val core = sanitizeCore(category).ifEmpty { category.trim() }
        val leagueLabel = league?.trim()?.uppercase().orEmpty()
        val prefix = if (leagueLabel.isNotEmpty() && !core.uppercase().contains(leagueLabel)) {
            "$leagueLabel "
        } else {
            ""
        }
        return "US: ${prefix}${core.uppercase()} SCHEDULE ᴸᴵⱽᴱ"
    }

    private fun formatAdult(channelName: String): String {
        var title = channelName.replace("\"", "'").trim()
        title = categorySuffixRe.replace(title, "").trim()
        title = resolutionSuffixRe.replace(title, "").trim()
        if (title.startsWith("18+")) return title.uppercase()
        return title.uppercase()
    }

    private fun sanitizeCore(channelName: String): String {
        var title = channelName.replace("\"", "'").trim()
        title = categorySuffixRe.replace(title, "").trim()
        title = geoBlockedRe.replace(title, "").trim()
        title = resolutionSuffixRe.replace(title, "").trim()
        repeat(4) {
            val next = stripTrailingNoise(title)
            if (next == title) return@repeat
            title = next
        }
        return multiSpaceRe.replace(title, " ").trim()
    }

    private fun stripTrailingNoise(title: String): String {
        var result = providerTagRe.replace(title, "").trim()
        result = trailingCountryRe.replace(result, "").trim()
        result = flagSuffixRe.replace(result, "").trim()
        result = orphanFlagRe.replace(result, "").trim()
        return result
    }

    /** Flags left mid-title after resolution/provider stripping. */
    private val orphanFlagRe = Regex("""[🇺🇸🇬🇧🇨🇦🇦🇺🇳🇿🇩🇪🇫🇷🇮🇹🇪🇸🇵🇱🇬🇷🇶🇦🇮🇱🇦🇪🇷🇸🇭🇷🇧🇦🇧🇬🇿🇦🇩🇰🇵🇹🇲🇽🇸🇪🇨🇿🇳🇱🇹🇷🇧🇷🇲🇾🇷🇴🇦🇷🇨🇾🇷🇺🇮🇳🇮🇪🇵🇰🇭🇺🇪🇬🇦🇹🇧🇩🇨🇱🇺🇾🇨🇴🌐🌍🏴]""")

    private fun countryPrefix(countryCode: String): String = when (countryCode.uppercase()) {
        "US" -> "US"
        "UK" -> "UK"
        "CA" -> "CA"
        "AU" -> "AU"
        "NZ" -> "NZ"
        "DE" -> "DE"
        "FR" -> "FR"
        "IT" -> "IT"
        "ES" -> "ES"
        "INT", "" -> "INT"
        else -> countryCode.uppercase().ifBlank { "INT" }
    }

    private fun applySpellingFixes(title: String): String {
        var result = title
        for ((wrong, right) in spellingFixes) {
            result = result.replace(wrong, right, ignoreCase = false)
        }
        return result
    }
}
