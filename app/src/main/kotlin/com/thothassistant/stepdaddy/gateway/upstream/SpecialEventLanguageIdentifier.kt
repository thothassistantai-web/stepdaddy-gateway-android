package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Detects ISO 639-1 language codes for Special Events from stream labels,
 * event titles, categories, and upstream URLs.
 */
object SpecialEventLanguageIdentifier {
    data class Context(
        val eventTitle: String = "",
        val streamLabel: String = "",
        val category: String = "",
        val league: String = "",
        val eventSourceUrl: String? = null,
        val siteLocale: String? = null,
    )

    private val explicitTagRe = Regex(
        """\[(FR|ES|EN|DE|IT|PT)\]|(?:\(|\b)(français|french|español|espanol|spanish|english|deutsch|german|italiano|italian|português|portugues|portuguese)(?:\)|\b)""",
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

    private val portugueseChannelRe = Regex(
        """\b(?:sport\s*tv|rtp\b|benfica\s*tv|porto\s*canal|dazn\s*pt)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val germanChannelRe = Regex(
        """\b(?:sky\s*sport\s*de|dazn\s*de|eurosport\s*de|sport1\b|ard\b|zdf\b)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val italianChannelRe = Regex(
        """\b(?:sky\s*sport\s*it|dazn\s*it|rai\s*sport|mediaset\s*sport)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val frenchDiacriticsRe = Regex("""[àâäéèêëïîôùûüçœæ]""", RegexOption.IGNORE_CASE)
    private val spanishDiacriticsRe = Regex("""[ñáéíóúü¿¡]""", RegexOption.IGNORE_CASE)

    private val urlLocaleRe = Regex(
        """thetvapp\.link/(?:fr|es|de|it|pt)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns ISO 639-1 code (`en`, `fr`, `es`, …) or `null` when unknown. */
    fun identify(ctx: Context): String? {
        val corpus = buildList {
            add(ctx.eventTitle)
            add(ctx.streamLabel)
            add(ctx.category)
            add(ctx.league)
        }.filter { it.isNotBlank() }.joinToString(" ")

        explicitTag(corpus)?.let { return it }
        channelPattern(corpus)?.let { return it }

        if (frenchDiacriticsRe.containsMatchIn(corpus)) return "fr"
        if (spanishDiacriticsRe.containsMatchIn(corpus)) return "es"

        localeFromUrl(ctx.eventSourceUrl)?.let { return it }
        normalizeLocale(ctx.siteLocale)?.let { return it }

        if (corpus.isBlank()) return null
        return "en"
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

    private fun explicitTag(corpus: String): String? {
        val match = explicitTagRe.find(corpus) ?: return null
        return when (match.groupValues[1].ifEmpty { match.groupValues[2] }.lowercase()) {
            "fr", "français", "french" -> "fr"
            "es", "español", "espanol", "spanish" -> "es"
            "en", "english" -> "en"
            "de", "deutsch", "german" -> "de"
            "it", "italiano", "italian" -> "it"
            "pt", "português", "portugues", "portuguese" -> "pt"
            else -> null
        }
    }

    private fun channelPattern(corpus: String): String? = when {
        frenchChannelRe.containsMatchIn(corpus) -> "fr"
        spanishChannelRe.containsMatchIn(corpus) -> "es"
        portugueseChannelRe.containsMatchIn(corpus) -> "pt"
        germanChannelRe.containsMatchIn(corpus) -> "de"
        italianChannelRe.containsMatchIn(corpus) -> "it"
        else -> null
    }

    private fun localeFromUrl(eventSourceUrl: String?): String? {
        val url = eventSourceUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val match = urlLocaleRe.find(url) ?: return null
        return when (match.value.lowercase().substringAfter("thetvapp.link/").take(2)) {
            "fr" -> "fr"
            "es" -> "es"
            "de" -> "de"
            "it" -> "it"
            "pt" -> "pt"
            else -> null
        }
    }

    private fun normalizeLocale(raw: String?): String? {
        val token = raw?.trim()?.lowercase().orEmpty()
        if (token.isEmpty()) return null
        return when {
            token.startsWith("fr") -> "fr"
            token.startsWith("es") -> "es"
            token.startsWith("en") -> "en"
            token.startsWith("de") -> "de"
            token.startsWith("it") -> "it"
            token.startsWith("pt") -> "pt"
            else -> null
        }
    }
}
