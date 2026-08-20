package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Orders live event rows in [GroupTitleResolver.SPECIAL_EVENTS].
 * Guide channels (`dlhd-guide:*`) sort A–Z by playlist display name; each guide sits
 * directly above its category's stream rows.
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

    fun leagueFromCategoryOrTitle(category: String, title: String): String {
        val colon = title.indexOf(':')
        if (colon in 1 until title.length - 1) {
            val prefix = title.substring(0, colon).trim()
            if (prefix.isNotEmpty()) return normalizeLeague(prefix)
        }
        return normalizeLeague(category)
    }

    /**
     * Sort key for live/upcoming event rows — live (on-air) before upcoming, then by start time.
     * Lower values sort first.
     */
    fun eventWindowSortKey(startMs: Long, stopMs: Long, nowMs: Long): Long {
        if (!SpecialEventLifecycle.isActive(startMs, stopMs, nowMs)) return Long.MAX_VALUE
        val isLive = startMs <= nowMs
        val tier = if (isLive) 0L else 1L
        return (tier shl 48) or (startMs and 0x0000_FFFF_FFFF_FFFFL)
    }

    fun streamWindowSortKey(channel: SupplementChannel, nowMs: Long): Long {
        val start = channel.eventStartMs ?: return Long.MAX_VALUE
        val stop = channel.eventStopMs ?: return Long.MAX_VALUE
        return eventWindowSortKey(start, stop, nowMs)
    }

    fun guideBlockEventSortKey(
        guideId: String,
        guideProgrammes: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long,
    ): Long {
        val rows = guideProgrammes[guideId].orEmpty()
        return rows.minOfOrNull { eventWindowSortKey(it.startMs, it.stopMs, nowMs) } ?: Long.MAX_VALUE
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

    /** Xtream-style playlist title for a guide row (used for A–Z guide ordering). */
    fun guideDisplayName(supplement: SupplementChannel): String {
        val categoryName = dlhdCategoryName(supplement).orEmpty()
        val category = SpecialEventCategoryEmoji.stripLeadingEmoji(categoryName)
        return XtreamCategoryTitleFormatter.formatGuideSchedule(category, supplement.providerTag)
    }

    /** Block key shared by a guide and its category events (A–Z by Xtream display name). */
    private fun dlhdGuideBlockKey(categoryName: String, providerTag: String?): String {
        val category = SpecialEventCategoryEmoji.stripLeadingEmoji(categoryName)
        return XtreamCategoryTitleFormatter.formatGuideSchedule(category, providerTag).lowercase()
    }

    /**
     * Lexicographic block key for DaddyLive guide + event rows.
     * Guides sort A–Z by playlist display name; events share their guide's key.
     */
    fun guideBlockSortKey(supplement: SupplementChannel): String = when {
        supplement.id.startsWith("dlhd-guide:") ->
            guideDisplayName(supplement).lowercase()
        supplement.id.startsWith("dlhd-event:") -> {
            val key = dlhdCategoryName(supplement)
                ?.let { dlhdGuideBlockKey(it, supplement.providerTag) }
                .orEmpty()
            if (key.isEmpty()) {
                "\uFFFE${supplement.name.lowercase()}"
            } else {
                key
            }
        }
        else -> ""
    }

    /** Slot within a guide block: guide first, then category streams by name. */
    fun supplementIntraSlot(supplement: SupplementChannel): Int = when {
        supplement.id.startsWith("dlhd-guide:") -> 0
        supplement.id.startsWith("dlhd-event:") ->
            1 + supplement.name.lowercase().hashCode().and(0x3FF)
        else -> 0
    }

    /**
     * Playlist / channel-number order within [GroupTitleResolver.SPECIAL_EVENTS].
     * Prefer [guideBlockSortKey] + [supplementIntraSlot] for stable A–Z guide ordering.
     */
    fun supplementPlaylistOrder(supplement: SupplementChannel): Int {
        if (!supplement.id.startsWith("dlhd-guide:") &&
            !supplement.id.startsWith("dlhd-event:")
        ) {
            return 0
        }
        if (guideBlockSortKey(supplement).isNotEmpty()) {
            return supplementIntraSlot(supplement)
        }
        return 1_000_000 +
            sortKey(supplement.providerTag, supplement.name, supplement.eventSourceUrl)
    }

    private fun leagueSortIndex(providerTag: String?, channelName: String, eventUrl: String?): Int {
        val league = normalizeLeague(
            providerTag?.trim().orEmpty().ifEmpty {
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
