package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context

/**
 * Maps iptv-org playlist entries into flat [GroupTitleResolver] categories and DaddyLive-style tags.
 */
class IptvOrgChannelResolver(
    private val catalog: IptvOrgChannelLookup,
) {
    constructor(context: Context) : this(IptvOrgChannelCatalog(context))

    fun buildTags(entry: M3uParser.Entry, playlistFile: String): List<String> {
        val tags = mutableListOf<String>()
        val row = catalog.lookup(entry.tvgId)

        if (row?.isNsfw == true) {
            tags += "#nsfw"
        }

        row?.categories?.forEach { category ->
            tags += "#$category"
            if (category.equals("xxx", ignoreCase = true)) {
                tags += "#nsfw"
            }
        }

        countryMarker(row?.country, playlistFile)?.let { tags += it }

        if (playlistFile == "us_local.m3u") {
            tags += "#local"
        }

        if (row == null || row.categories.isEmpty()) {
            applyNameHeuristics(entry.name, tags)
        }

        return tags.distinct()
    }

    fun resolve(entry: M3uParser.Entry, playlistFile: String): GroupTitleResolver.Resolution {
        val tags = buildTags(entry, playlistFile)
        var resolution = GroupTitleResolver.resolve(entry.name, tags)
        val row = catalog.lookup(entry.tvgId) ?: return resolution

        val categories = row.categories.toSet()
        resolution = when {
            PremiumMovieChannelMatcher.matches(entry.name) ->
                resolution.copy(
                    groupTitle = GroupTitleResolver.MOVIES,
                    categoryLabel = GroupTitleResolver.MOVIES,
                )
            categories.contains("movies") &&
                resolution.groupTitle == GroupTitleResolver.ENTERTAINMENT ->
                resolution.copy(
                    groupTitle = GroupTitleResolver.MOVIES,
                    categoryLabel = GroupTitleResolver.MOVIES,
                )
            categories.contains("education") &&
                resolution.groupTitle == GroupTitleResolver.ENTERTAINMENT ->
                resolution.copy(
                    groupTitle = GroupTitleResolver.DOCUMENTARY,
                    categoryLabel = GroupTitleResolver.DOCUMENTARY,
                )
            else -> resolution
        }
        return resolution
    }

    private fun countryMarker(dbCountry: String?, playlistFile: String): String? {
        val code = normalizeCountryCode(dbCountry?.takeIf { it.isNotBlank() })
            ?: playlistCountryCode(playlistFile)
            ?: return null
        return GroupTitleResolver.flagForCode(code)?.let { flag -> "$flag" }
    }

    private fun normalizeCountryCode(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return when (code.uppercase()) {
            "GB" -> "UK"
            else -> code.uppercase()
        }
    }

    private fun playlistCountryCode(playlistFile: String): String? =
        when {
            playlistFile.startsWith("uk") -> "UK"
            playlistFile.startsWith("us") -> "US"
            else -> null
        }

    private fun applyNameHeuristics(name: String, tags: MutableList<String>) {
        val lower = name.lowercase()
        when {
            lower.contains("news") || lower.contains("noticias") -> tags += "#news"
            listOf("sport", "espn", "nfl", "nba", "mlb", "soccer", "football")
                .any { lower.contains(it) } -> tags += "#sports"
            lower.contains("vevo") || lower.contains("music") -> tags += "#music"
            lower.contains("kids") || lower.contains("cartoon") -> tags += "#kids"
            lower.contains("movie") || lower.contains("cinema") -> tags += "#movies"
            lower.contains("documentary") || lower.contains("history") -> tags += "#documentary"
        }
    }
}
