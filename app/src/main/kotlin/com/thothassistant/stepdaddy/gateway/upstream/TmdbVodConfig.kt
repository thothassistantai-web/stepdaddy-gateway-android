package com.thothassistant.stepdaddy.gateway.upstream

object TmdbVodConfig {
    const val GROUP_TITLE = "🎬 Movies"
    const val PROVIDER_TAG = "TMDB"
    const val ID_PREFIX = "vod:tmdb:"

    /** Max unique movies merged into playlists per sync. */
    const val MAX_CATALOG_SIZE = 50

    const val TMDB_API_BASE = "https://api.themoviedb.org/3/"
    const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"

    const val CACHE_STALE_MS = 6 * 60 * 60 * 1000L

    const val VIDSRC_EMBED_BASE = "https://vidsrc-embed.ru"
    const val VIDSRC_REFERER = "https://vidsrc-embed.ru/"

    fun supplementId(tmdbId: Int): String = "$ID_PREFIX$tmdbId"

    fun tmdbIdFromSupplementId(id: String): String? =
        id.removePrefix(ID_PREFIX).takeIf { it.isNotEmpty() && it != id }

    fun posterUrl(posterPath: String?): String? =
        posterPath?.trim()?.takeIf { it.isNotEmpty() }?.let { "$POSTER_BASE$it" }

    fun displayTitle(title: String, releaseDate: String?): String {
        val year = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        return if (year != null) "$title ($year)" else title
    }
}
