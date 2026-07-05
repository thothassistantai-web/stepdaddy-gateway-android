package com.thothassistant.stepdaddy.gateway.upstream

/** Xtream-style VOD category shelves for M3U group-title and player_api categories. */
object VodCategoryResolver {
    const val LATEST_MOVIES = "🎬 Latest Movies"
    const val LATEST_SHOWS = "📺 Latest Shows"

    private val GENRE_ALIASES = mapOf(
        "science fiction" to "Sci-Fi",
        "sciencefiction" to "Sci-Fi",
        "tv movie" to "TV Movie",
        "action & adventure" to "Action",
        "action and adventure" to "Action",
    )

    fun movieGroupTitle(genre: String?): String {
        val label = primaryGenre(genre) ?: return LATEST_MOVIES
        return "🎬 $label"
    }

    fun seriesGroupTitle(genre: String?, showTitle: String? = null, showShelf: Boolean = false): String {
        if (showShelf && !showTitle.isNullOrBlank()) {
            return "📺 ${TmdbVodConfig.cleanListTitle(showTitle)}"
        }
        val label = primaryGenre(genre) ?: return LATEST_SHOWS
        return "📺 $label"
    }

    /** nextbox.uno section title as Xtream category shelf. */
    fun nextboxMovieGroupTitle(category: String): String {
        val trimmed = category.trim().ifBlank { return LATEST_MOVIES }
        return if (trimmed.startsWith("🎬")) trimmed else "🎬 $trimmed"
    }

    fun nextboxSeriesGroupTitle(category: String): String {
        val trimmed = category.trim().ifBlank { return LATEST_SHOWS }
        return if (trimmed.startsWith("📺")) trimmed else "📺 $trimmed"
    }

    fun categoryId(groupTitle: String): String =
        groupTitle.lowercase()
            .replace(Regex("""[^\w\s-]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "_")
            .ifBlank { "vod" }

    fun primaryGenre(raw: String?): String? {
        val first = raw?.split("/", "·", "|", ",")
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        val key = first.lowercase()
        GENRE_ALIASES[key]?.let { return it }
        return first.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }
}
