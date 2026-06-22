package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Orders live event rows in [GroupTitleResolver.SPECIAL_EVENTS] by sport/league type.
 * Guide channels are kept directly above their category's stream rows.
 */
object SpecialEventSort {
    private val LEAGUE_PRIORITY = listOf(
        "NFL",
        "NCAA",
        "NBA",
        "NHL",
        "MLB",
        "MLS",
        "UFC",
        "BOXING",
        "WWE",
        "SOCCER",
        "F1",
        "NASCAR",
        "TENNIS",
        "GOLF",
        "RUGBY",
        "CRICKET",
        "OTHER",
    )

    private val nameLeagueHints = listOf(
        "NFL" to Regex("""\bNFL\b""", RegexOption.IGNORE_CASE),
        "NBA" to Regex("""\bNBA\b""", RegexOption.IGNORE_CASE),
        "NHL" to Regex("""\bNHL\b""", RegexOption.IGNORE_CASE),
        "MLB" to Regex("""\bMLB\b""", RegexOption.IGNORE_CASE),
        "UFC" to Regex("""\bUFC\b""", RegexOption.IGNORE_CASE),
        "MLS" to Regex("""\bMLS\b""", RegexOption.IGNORE_CASE),
        "F1" to Regex("""\bF1\b|Formula\s*1""", RegexOption.IGNORE_CASE),
        "NASCAR" to Regex("""\bNASCAR\b""", RegexOption.IGNORE_CASE),
        "SOCCER" to Regex("""\bSoccer\b|Premier League|La Liga|Champions League""", RegexOption.IGNORE_CASE),
    )

    fun leagueFromEventUrl(eventUrl: String): String {
        val path = eventUrl.substringAfter("thetvapp.link/", "").trim('/')
        val slug = path.substringBefore('/').trim()
        if (slug.isEmpty()) return "OTHER"
        return normalizeLeague(slug)
    }

    fun leagueFromCategoryOrTitle(category: String, title: String): String {
        val colon = title.indexOf(':')
        if (colon in 1 until title.length - 1) {
            val prefix = title.substring(0, colon).trim()
            if (prefix.isNotEmpty()) return normalizeLeague(prefix)
        }
        return normalizeLeague(category)
    }

    fun sortKey(providerTag: String?, channelName: String, eventUrl: String? = null): Int {
        val leagueIndex = leagueSortIndex(providerTag, channelName, eventUrl)
        return leagueIndex * 10_000 + channelName.lowercase().hashCode().and(0xFFF)
    }

    /** Sort key for a DaddyLive schedule category block (guide + its streams). */
    fun categoryBlockSortKey(categoryName: String, providerTag: String? = null): Int =
        leagueSortIndex(providerTag, categoryName, eventUrl = null)

    fun dlhdCategorySlug(supplement: SupplementChannel): String? = when {
        supplement.id.startsWith("dlhd-guide:") -> supplement.id.removePrefix("dlhd-guide:")
        supplement.id.startsWith("dlhd-event:") -> {
            val raw = supplement.eventSourceUrl?.substringBefore('|')?.trim().orEmpty()
            if (raw.isEmpty()) null else SpecialEventsMerger.slugify(raw)
        }
        else -> null
    }

    fun dlhdCategoryName(supplement: SupplementChannel): String? = when {
        supplement.id.startsWith("dlhd-guide:") -> supplement.name.removeSuffix(" Schedule").trim()
        supplement.id.startsWith("dlhd-event:") -> supplement.eventSourceUrl?.substringBefore('|')?.trim()
        else -> null
    }

    /**
     * Playlist / channel-number order within [GroupTitleResolver.SPECIAL_EVENTS].
     * Each guide row is immediately followed by stream rows from the same schedule category.
     */
    fun supplementPlaylistOrder(supplement: SupplementChannel): Int {
        if (!supplement.id.startsWith("dlhd-guide:") &&
            !supplement.id.startsWith("dlhd-event:") &&
            !supplement.id.startsWith("sport:")
        ) {
            return 0
        }
        dlhdCategorySlug(supplement)?.let { _ ->
            val categoryName = dlhdCategoryName(supplement).orEmpty()
            val block = categoryBlockSortKey(categoryName, supplement.providerTag)
            val slot = when {
                supplement.id.startsWith("dlhd-guide:") -> 0
                else -> 1 + supplement.name.lowercase().hashCode().and(0x3FF)
            }
            return block * 10_000 + slot
        }
        return 1_000_000 +
            sortKey(supplement.providerTag, supplement.name, supplement.eventSourceUrl)
    }

    private fun leagueSortIndex(providerTag: String?, channelName: String, eventUrl: String?): Int {
        val league = normalizeLeague(
            providerTag?.trim().orEmpty().ifEmpty {
                eventUrl?.let { leagueFromEventUrl(it) }.orEmpty()
            }.ifEmpty {
                guessLeagueFromName(channelName)
            },
        )
        return LEAGUE_PRIORITY.indexOf(league).let { index ->
            if (index < 0) LEAGUE_PRIORITY.lastIndex else index
        }
    }

    private fun guessLeagueFromName(channelName: String): String {
        for ((league, pattern) in nameLeagueHints) {
            if (pattern.containsMatchIn(channelName)) return league
        }
        return "OTHER"
    }

    fun normalizeLeague(raw: String): String {
        val token = raw.trim().replace('-', ' ').replace('_', ' ')
        val upper = token.uppercase()
        return when {
            upper in LEAGUE_PRIORITY -> upper
            upper.startsWith("NBA") -> "NBA"
            upper.startsWith("NFL") -> "NFL"
            upper.startsWith("NHL") -> "NHL"
            upper.startsWith("MLB") -> "MLB"
            upper.startsWith("MLS") -> "MLS"
            upper.startsWith("UFC") -> "UFC"
            upper.contains("SOCCER") || upper.contains("FOOTBALL") -> "SOCCER"
            upper.contains("BOXING") -> "BOXING"
            upper.contains("WWE") -> "WWE"
            upper.contains("F1") || upper.contains("FORMULA") -> "F1"
            upper.contains("NASCAR") -> "NASCAR"
            upper.contains("TENNIS") -> "TENNIS"
            upper.contains("GOLF") -> "GOLF"
            upper.contains("RUGBY") -> "RUGBY"
            upper.contains("CRICKET") -> "CRICKET"
            upper.contains("NCAA") || upper.contains("COLLEGE") -> "NCAA"
            else -> upper.replace(Regex("\\s+"), " ").take(24).ifBlank { "OTHER" }
        }
    }
}
