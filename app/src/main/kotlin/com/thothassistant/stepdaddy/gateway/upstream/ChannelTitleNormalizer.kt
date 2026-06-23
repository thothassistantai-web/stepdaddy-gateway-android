package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Normalizes TiviMate display titles: legacy flag suffix or Xtream-style category names.
 */
object ChannelTitleNormalizer {
    private val categorySuffixRe = Regex(" \\[[^\\]]+\\]$")
    private val multiSpaceRe = Regex("\\s+")

    private val spellingFixes = mapOf(
        "Israe" to "Israel",
        "Espanol" to "Español",
        "espanol" to "español",
    )

    fun displayTitle(
        channelName: String,
        resolution: GroupTitleResolver.Resolution,
        style: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        source: PlaylistTitleSource = PlaylistTitleSource.CABLE,
    ): String {
        if (style == PlaylistTitleStyle.XTREAM_CATEGORY) {
            return XtreamCategoryTitleFormatter.format(channelName, resolution, source)
        }
        return legacyDisplayTitle(channelName, resolution)
    }

    fun supplementDisplayTitle(
        channelName: String,
        resolution: GroupTitleResolver.Resolution,
        providerTag: String?,
        style: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        source: PlaylistTitleSource = PlaylistTitleSource.FAST,
        eventSourceUrl: String? = null,
    ): String {
        if (style == PlaylistTitleStyle.LEGACY) {
            val base = legacyDisplayTitle(channelName, resolution)
            val tag = providerTag?.trim().orEmpty()
            if (tag.isEmpty() || base.endsWith(tag, ignoreCase = true)) return base
            return "$base $tag"
        }
        if (source == PlaylistTitleSource.ADULT_SWIM_247) {
            return XtreamCategoryTitleFormatter.formatAdultSwimMarathon(channelName)
        }
        if (source == PlaylistTitleSource.SPECIAL_EVENT_GUIDE) {
            val category = SpecialEventCategoryEmoji.stripLeadingEmoji(
                channelName.removeSuffix(" Schedule").trim(),
            )
            return XtreamCategoryTitleFormatter.formatGuideSchedule(category, providerTag)
        }
        if (source == PlaylistTitleSource.SPECIAL_EVENT) {
            val league = providerTag?.trim().orEmpty().ifEmpty {
                eventSourceUrl?.let { SpecialEventSort.leagueFromEventUrl(it) }
            }
            val eventTitle = DlhdEventSourceMeta.parse(eventSourceUrl)?.displayTitle()
                ?.takeIf { it.isNotBlank() }
                ?: channelName
            return XtreamCategoryTitleFormatter.formatSpecialEvent(eventTitle, league)
        }
        return XtreamCategoryTitleFormatter.format(channelName, resolution, source)
    }

    private fun legacyDisplayTitle(channelName: String, resolution: GroupTitleResolver.Resolution): String {
        var title = channelName.replace("\"", "'").trim()
        title = categorySuffixRe.replace(title, "").trim()
        title = applySpellingFixes(title)

        if (!resolution.appendCountrySuffix || resolution.countryCode.isBlank()) {
            return title
        }

        title = stripDuplicateCountrySuffix(title, resolution.countryCode, resolution.flagEmoji)
        val flag = resolution.flagEmoji ?: return title
        val suffix = "$flag ${resolution.countryCode}"
        if (title.endsWith(suffix, ignoreCase = false)) return title
        return "$title $suffix".trim()
    }

    private fun stripDuplicateCountrySuffix(title: String, countryCode: String, flagEmoji: String?): String {
        var result = title
        val flag = flagEmoji ?: GroupTitleResolver.flagForCode(countryCode)
        if (flag != null) {
            val withCode = "$flag $countryCode"
            if (result.endsWith(withCode, ignoreCase = false)) {
                return result.dropLast(withCode.length).trimEnd()
            }
            if (result.endsWith(flag, ignoreCase = false)) {
                return result.dropLast(flag.length).trimEnd()
            }
        }
        return result
    }

    private fun applySpellingFixes(title: String): String {
        var result = title
        for ((wrong, right) in spellingFixes) {
            result = result.replace(wrong, right, ignoreCase = false)
        }
        return multiSpaceRe.replace(result, " ").trim()
    }
}
