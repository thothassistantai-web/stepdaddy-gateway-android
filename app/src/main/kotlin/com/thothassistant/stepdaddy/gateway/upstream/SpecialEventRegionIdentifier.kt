package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Detects ISO-like region codes for Special Events (US, UK, CA, …) from titles,
 * stream labels, categories, broadcaster names, and upstream URLs.
 */
object SpecialEventRegionIdentifier {
    data class Context(
        val eventTitle: String = "",
        val streamLabel: String = "",
        val category: String = "",
        val league: String = "",
        val eventSourceUrl: String? = null,
    )

    private val REGION_CODES = setOf(
        "US", "UK", "CA", "AU", "NZ", "DE", "FR", "IT", "ES", "IE", "MX", "BR", "INT",
    )

    private val explicitTagRe = Regex(
        """\[(US|UK|CA|AU|NZ|DE|FR|IT|ES|IE|MX|BR|INT)\]""",
        RegexOption.IGNORE_CASE,
    )

    private val streamLabelRegionRe = Regex(
        """\b(US|UK|CA|AU|NZ|DE|FR|IT|ES|IE|MX|BR)\b(?:\s*[-–|/]\s*|\s+(?:feed|link|stream|source)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val canadianBroadcasterRe = Regex(
        """\b(?:tva\s*sports?|tsn\b|rds\b|sportsnet|cbc\b|ctv\b|global\s*tv|citytv|télé|tele[- ]?quebec|crave\s*sports?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val ukBroadcasterRe = Regex(
        """\b(?:sky\s*sports?|bt\s*sport|bbc\s*sport|itv\s*sport|premier\s*sports|tnt\s*sports|dazn\s*uk|amazon\s*prime\s*uk)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val usBroadcasterRe = Regex(
        """\b(?:espn\b|fox\s*sports|nbc\s*sports|cbs\s*sports|mlb\.tv|nba\s*league\s*pass|nfl\s*network|peacock|max\s*sports|fubo|directv|bally\s*sports|regional\s*sports)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val australianBroadcasterRe = Regex(
        """\b(?:kayo|fox\s*league|optus\s*sport|stan\s*sport|channel\s*9|channel\s*10)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val categoryRegionRe = Regex(
        """\b(?:uk|canadian?|australian?|mexican|irish|french|german|italian|spanish)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val urlRegionRe = Regex(
        """thetvapp\.link/(?:uk|ca|au|ie|mx|br|de|fr|es|it)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val usLeagueDefaults = setOf(
        "NFL", "NBA", "NHL", "MLB", "MLS", "NCAA", "UFC", "NASCAR", "WNBA", "BOXING", "WWE",
    )

    private val ukLeagueHints = Regex(
        """\b(?:EPL|Premier League|FA Cup|EFL|Championship|Scottish|Rugby Union|Six Nations)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns region code (`US`, `UK`, `CA`, …) or `null` when unknown. */
    fun identify(ctx: Context): String? {
        val corpus = buildList {
            add(ctx.eventTitle)
            add(ctx.streamLabel)
            add(ctx.category)
            add(ctx.league)
        }.filter { it.isNotBlank() }.joinToString(" ")

        prefixRegion(ctx.eventTitle)?.let { return it }
        prefixRegion(ctx.category)?.let { return it }
        explicitTag(corpus)?.let { return it }
        streamLabelRegionRe.find(ctx.streamLabel)?.groupValues?.getOrNull(1)?.let {
            return normalizeCode(it)
        }
        broadcasterRegion(corpus)?.let { return it }
        categoryHintRegion(corpus)?.let { return it }
        leagueDefaultRegion(ctx.league, corpus)?.let { return it }
        regionFromUrl(ctx.eventSourceUrl)?.let { return it }
        if (corpus.isBlank()) return null
        return "US"
    }

    fun identifyFromSupplement(
        name: String,
        providerTag: String?,
        eventSourceUrl: String?,
        streamLabel: String? = null,
    ): String? {
        val meta = DlhdEventSourceMeta.parse(eventSourceUrl)
        return identify(
            Context(
                eventTitle = meta?.title ?: name,
                streamLabel = streamLabel.orEmpty(),
                category = meta?.category.orEmpty(),
                league = providerTag.orEmpty(),
                eventSourceUrl = eventSourceUrl,
            ),
        )
    }

    fun flagForCode(code: String): String? = GroupTitleResolver.flagForCode(normalizeCode(code))

    fun normalizeCode(raw: String): String = ChannelCountrySort.normalizeCode(raw)

    private fun prefixRegion(text: String): String? = EventRegionMetadata.parseExplicitPrefix(text)

    private fun explicitTag(corpus: String): String? {
        val match = explicitTagRe.find(corpus) ?: return null
        return normalizeCode(match.groupValues[1])
    }

    private fun broadcasterRegion(corpus: String): String? = when {
        canadianBroadcasterRe.containsMatchIn(corpus) -> "CA"
        ukBroadcasterRe.containsMatchIn(corpus) -> "UK"
        australianBroadcasterRe.containsMatchIn(corpus) -> "AU"
        usBroadcasterRe.containsMatchIn(corpus) -> "US"
        else -> null
    }

    private fun categoryHintRegion(corpus: String): String? {
        val match = categoryRegionRe.find(corpus) ?: return null
        return when (match.value.lowercase()) {
            "uk" -> "UK"
            "canadian", "canada" -> "CA"
            "australian", "australia" -> "AU"
            "mexican" -> "MX"
            "irish" -> "IE"
            "french" -> "FR"
            "german" -> "DE"
            "italian" -> "IT"
            "spanish" -> "ES"
            else -> null
        }
    }

    private fun leagueDefaultRegion(league: String, corpus: String): String? {
        val normalized = SpecialEventSort.normalizeLeague(league)
        if (normalized in usLeagueDefaults) return "US"
        if (ukLeagueHints.containsMatchIn(corpus) || normalized == "SOCCER" && corpus.contains("Premier", ignoreCase = true)) {
            return "UK"
        }
        if (normalized == "SOCCER" && corpus.contains("MLS", ignoreCase = true)) return "US"
        return null
    }

    private fun regionFromUrl(eventSourceUrl: String?): String? {
        val url = eventSourceUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val match = urlRegionRe.find(url) ?: return null
        val token = match.value.lowercase().substringAfter("thetvapp.link/").take(2)
        return when (token) {
            "uk" -> "UK"
            "ca" -> "CA"
            "au" -> "AU"
            "ie" -> "IE"
            "mx" -> "MX"
            "br" -> "BR"
            "de" -> "DE"
            "fr" -> "FR"
            "es" -> "ES"
            "it" -> "IT"
            else -> null
        }
    }
}
