package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Region metadata for Special Events: explicit US/UK/CA prefixes, country codes,
 * and M3U `tvg-country` attribute helpers. Tier-2 [PlaylistBuilder] integration surface.
 */
object EventRegionMetadata {
    data class Result(
        val countryCode: String,
        val flagEmoji: String?,
        /** Region parsed from an explicit `US:` / `UK:` / `CA:` title prefix, if any. */
        val explicitPrefix: String? = null,
    )

    /** Primary regions surfaced in Xtream-style titles and playlist metadata. */
    val PRIMARY_REGION_CODES = setOf("US", "UK", "CA")

    private val explicitPrefixRe = Regex(
        """^(US|UK|CA|AU|NZ|DE|FR|IT|ES|IE|MX|BR|INT)\s*:\s""",
        RegexOption.IGNORE_CASE,
    )

    private val streamLabelSuffixRe = Regex("""\(([^)]+)\)\s*$""")
    private val genericLinkLabelRe = Regex("""Link\s*-\s*\d+""", RegexOption.IGNORE_CASE)

    /** Parses explicit `US:`, `UK:`, `CA:` … prefix from title or category text. */
    fun parseExplicitPrefix(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val match = explicitPrefixRe.find(trimmed) ?: return null
        return normalizeCode(match.groupValues[1])
    }

    fun normalizeCode(raw: String): String = ChannelCountrySort.normalizeCode(raw)

    fun flagForCode(code: String): String? = GroupTitleResolver.flagForCode(normalizeCode(code))

    fun resolve(
        eventTitle: String = "",
        streamLabel: String = "",
        category: String = "",
        league: String = "",
        eventSourceUrl: String? = null,
        persistedRegionCode: String? = null,
    ): Result {
        val explicitPrefix = parseExplicitPrefix(eventTitle) ?: parseExplicitPrefix(category)
        persistedRegionCode?.trim()?.takeIf { it.isNotEmpty() }?.let { persisted ->
            val code = normalizeCode(persisted)
            return Result(
                countryCode = code,
                flagEmoji = flagForCode(code),
                explicitPrefix = explicitPrefix,
            )
        }
        val identified = SpecialEventRegionIdentifier.identify(
            SpecialEventRegionIdentifier.Context(
                eventTitle = eventTitle,
                streamLabel = streamLabel,
                category = category,
                league = league,
                eventSourceUrl = eventSourceUrl,
            ),
        )
        val code = normalizeCode(identified ?: "US")
        return Result(
            countryCode = code,
            flagEmoji = flagForCode(code),
            explicitPrefix = explicitPrefix,
        )
    }

    fun resolveFromSupplement(supplement: SupplementChannel): Result {
        val meta = DlhdEventSourceMeta.parse(supplement.eventSourceUrl)
        return resolve(
            eventTitle = meta?.title ?: supplement.name,
            streamLabel = streamLabelFromName(supplement.name),
            category = meta?.category.orEmpty(),
            league = supplement.providerTag.orEmpty(),
            eventSourceUrl = supplement.eventSourceUrl,
            persistedRegionCode = supplement.regionCode,
        )
    }

    /** M3U EXTINF `tvg-country="XX"` attribute, or null when empty or INT. */
    fun tvgCountryAttribute(countryCode: String): String? {
        val code = normalizeCode(countryCode)
        if (code.isEmpty() || code == "INT") return null
        return """tvg-country="${escapeAttributeValue(code)}""""
    }

    fun escapeAttributeValue(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()

    private fun streamLabelFromName(name: String): String {
        val match = streamLabelSuffixRe.find(name.trim()) ?: return ""
        val label = match.groupValues[1].trim()
        if (label.isEmpty() || genericLinkLabelRe.matches(label)) return ""
        return label
    }
}
