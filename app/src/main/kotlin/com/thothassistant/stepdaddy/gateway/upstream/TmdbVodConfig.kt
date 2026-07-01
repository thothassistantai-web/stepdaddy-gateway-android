package com.thothassistant.stepdaddy.gateway.upstream

object TmdbVodConfig {
    const val GROUP_TITLE = "🎬 Movies"
    const val SERIES_GROUP_TITLE = "📺 Shows"
    const val PROVIDER_TAG = "VOD"
    const val ID_PREFIX = "vod:tmdb:"
    const val SERIES_ID_PREFIX = "vod:series:"

    /** Max unique movies merged into playlists per sync. */
    const val MAX_CATALOG_SIZE = 150

    /** Max latest episodes merged into playlists per sync. */
    const val MAX_SERIES_CATALOG_SIZE = 150

    const val TMDB_API_BASE = "https://api.themoviedb.org/3/"
    /** Portrait poster size used by major Xtream VOD providers. */
    const val POSTER_BASE = "https://image.tmdb.org/t/p/w600_and_h900_bestv2"
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    const val METAHUB_POSTER_BASE = "https://images.metahub.space/poster/large"

    const val CACHE_STALE_MS = 6 * 60 * 60 * 1000L

    /** Enrich every catalog row that has an IMDB id (Cinemeta + Metahub posters). */
    const val MAX_CINEMETA_ENRICH = MAX_CATALOG_SIZE

    @Deprecated("Use VsembedConfig.embedBase()", ReplaceWith("VsembedConfig.embedBase()"))
    val VIDSRC_EMBED_BASE: String get() = VsembedConfig.embedBase()

    @Deprecated("Use VsembedConfig.embedReferer()", ReplaceWith("VsembedConfig.embedReferer()"))
    val VIDSRC_REFERER: String get() = VsembedConfig.embedReferer()

    val EMBED_REFERER: String get() = VsembedConfig.embedReferer()

    fun supplementId(tmdbId: Int): String = "$ID_PREFIX$tmdbId"

    fun tmdbIdFromSupplementId(id: String): String? =
        id.removePrefix(ID_PREFIX).takeIf { it.isNotEmpty() && it != id }

    fun seriesSupplementId(showTmdbId: Int, season: Int, episode: Int): String =
        "$SERIES_ID_PREFIX$showTmdbId:$season:$episode"

    data class SeriesEpisodeKey(
        val showTmdbId: Int,
        val season: Int,
        val episode: Int,
    )

    fun parseSeriesSupplementId(id: String): SeriesEpisodeKey? {
        if (!id.startsWith(SERIES_ID_PREFIX)) return null
        val parts = id.removePrefix(SERIES_ID_PREFIX).split(':')
        if (parts.size != 3) return null
        val showTmdbId = parts[0].toIntOrNull() ?: return null
        val season = parts[1].toIntOrNull() ?: return null
        val episode = parts[2].toIntOrNull() ?: return null
        return SeriesEpisodeKey(showTmdbId, season, episode)
    }

    fun episodeDisplayTitle(showTitle: String, season: Int, episode: Int): String {
        val s = season.toString().padStart(2, '0')
        val e = episode.toString().padStart(2, '0')
        return "${cleanListTitle(showTitle)} - S${s}E$e"
    }

    /** Xtream-style movie label: `Title (Year)`. */
    fun movieDisplayTitle(title: String, releaseDate: String?): String {
        val clean = cleanListTitle(title.trim())
        val year = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: parseListTitle(title).year
        return if (year != null) "$clean ($year)" else clean
    }

    data class ParsedListTitle(
        val title: String,
        val year: String? = null,
    )

    /** Split vsembed `Show Name 2019` into title + year. */
    fun parseListTitle(raw: String): ParsedListTitle {
        val trimmed = raw.trim()
        val match = Regex("""^(.+?)\s+(\d{4})$""").find(trimmed) ?: return ParsedListTitle(trimmed)
        val title = match.groupValues[1].trim().ifBlank { trimmed }
        val year = match.groupValues[2]
        return ParsedListTitle(title, year)
    }

    fun metahubPosterUrl(imdbId: String): String {
        val normalized = imdbId.trim()
        return "$METAHUB_POSTER_BASE/$normalized/img"
    }

    /** Prefer TMDB portrait URLs; upgrade Metahub/Cinemeta sizes when possible. */
    fun normalizePosterUrl(url: String?): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            trimmed.contains("image.tmdb.org") && trimmed.contains("/w500") ->
                trimmed.replace("/w500", "/w600_and_h900_bestv2")
            trimmed.contains("images.metahub.space/poster/small/") ->
                trimmed.replace("/poster/small/", "/poster/large/")
            trimmed.contains("images.metahub.space/poster/medium/") ->
                trimmed.replace("/poster/medium/", "/poster/large/")
            else -> trimmed
        }
    }

    fun posterUrl(posterPath: String?): String? =
        posterPath?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            if (path.startsWith("http")) normalizePosterUrl(path) else "$POSTER_BASE$path"
        }

    @Deprecated("Use movieDisplayTitle", ReplaceWith("movieDisplayTitle(title, releaseDate)"))
    fun displayTitle(title: String, releaseDate: String?): String = movieDisplayTitle(title, releaseDate)

    /** Strip trailing year suffix from vsembed list titles when present. */
    fun cleanListTitle(raw: String): String = parseListTitle(raw).title
}
