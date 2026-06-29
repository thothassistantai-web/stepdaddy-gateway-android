package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Detects ISO 639-1 language codes (`fr`, `en`, `es`) for Special Events from
 * playlist channel names and trailing broadcaster labels (e.g. `(TVA Sports)`).
 *
 * Used by [SpecialEventLanguageIdentifier] and [PlaylistBuilder.supplementLanguageCode].
 */
object EventLanguageResolver {
    private val streamLabelSuffixRe = Regex("""\(([^)]+)\)\s*$""")
    private val genericLinkLabelRe = Regex("""Link\s*-\s*\d+""", RegexOption.IGNORE_CASE)

    private val explicitTagRe = Regex(
        """\[(FR|ES|EN)\]|(?:\(|\b)(français|french|español|espanol|spanish|english)(?:\)|\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val frenchChannelRe = Regex(
        """\b(?:tva\s*sports?|tva\b|rds\b|rdi\b|radio[- ]?canada|télé|tele[- ]?quebec|tv5|rmc\s*sport|canal\+|l['']équipe|bein\s*sports?\s*fr|dazn\s*fr|eurosport\s*fr)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val spanishChannelRe = Regex(
        """\b(?:espn\s*deportes|univision|telemundo|movistar|gol\s*tv|tudn|fox\s*deportes|tyc\s*sports|directv\s*sports|dazn\s*es|bein\s*sports?\s*es|telecinco|cuatro|la\s*liga\s*tv)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val frenchDiacriticsRe = Regex("""[àâäéèêëïîôùûüçœæ]""", RegexOption.IGNORE_CASE)
    private val spanishDiacriticsRe = Regex("""[ñáéíóúü¿¡]""", RegexOption.IGNORE_CASE)

    /** Trailing parenthetical label, e.g. `NHL : Leafs (TVA Sports)` → `TVA Sports`. */
    fun parseStreamLabel(channelName: String): String? {
        val match = streamLabelSuffixRe.find(channelName.trim()) ?: return null
        val label = match.groupValues[1].trim()
        if (label.isEmpty() || genericLinkLabelRe.matches(label)) return null
        return label
    }

    /**
     * Returns ISO 639-1 code (`fr`, `en`, `es`) from an event channel name, or `null`
     * when the name is blank.
     */
    fun resolveFromChannelName(channelName: String): String? {
        val trimmed = channelName.trim()
        if (trimmed.isEmpty()) return null

        val streamLabel = parseStreamLabel(trimmed).orEmpty()
        val corpus = listOf(trimmed, streamLabel).filter { it.isNotBlank() }.joinToString(" ")

        explicitTag(corpus)?.let { return it }
        channelPattern(corpus)?.let { return it }

        if (frenchDiacriticsRe.containsMatchIn(corpus)) return "fr"
        if (spanishDiacriticsRe.containsMatchIn(corpus)) return "es"

        return "en"
    }

    /** XMLTV `tvg-language` token from ISO 639-1 (`fra`, `eng`, `spa`, …). */
    fun toTvgLanguageCode(iso6391: String): String = when (iso6391.trim().lowercase()) {
        "fr" -> "fra"
        "en" -> "eng"
        "es" -> "spa"
        else -> iso6391.trim().lowercase()
    }

    private fun explicitTag(corpus: String): String? {
        val match = explicitTagRe.find(corpus) ?: return null
        return when (match.groupValues[1].ifEmpty { match.groupValues[2] }.lowercase()) {
            "fr", "français", "french" -> "fr"
            "es", "español", "espanol", "spanish" -> "es"
            "en", "english" -> "en"
            else -> null
        }
    }

    private fun channelPattern(corpus: String): String? = when {
        frenchChannelRe.containsMatchIn(corpus) -> "fr"
        spanishChannelRe.containsMatchIn(corpus) -> "es"
        else -> null
    }
}
