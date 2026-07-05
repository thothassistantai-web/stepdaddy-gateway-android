package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Automatic duplicate management for VOD **movies** only.
 *
 * Applied at catalog build time ([TmdbVodCatalog.fetchCatalog]) before year sort.
 * Series/episodes are out of scope — see [TmdbVodSeriesCatalog].
 *
 * Identity (strongest signal wins when grouping):
 * 1. Same TMDB id — one row per [TmdbVodCatalog.Movie.tmdbId]
 * 2. Same IMDB id — collapses scraper rows that share `tt…` but disagree on TMDB id
 * 3. Normalized title + release year — case/punctuation/article insensitive
 *
 * Keeper selection within a duplicate group:
 * 1. Prefer row with IMDB id + stream quality (vsembed / playable resolver path)
 * 2. Prefer richer metadata (poster, overview, vote average, non-blank title)
 * 3. Merge shelf/genre labels from dropped rows into the keeper (` · `-separated)
 */
object VodMovieDedup {
    data class Result(
        val movies: List<TmdbVodCatalog.Movie>,
        val inputCount: Int,
        val removedCount: Int,
    ) {
        val outputCount: Int get() = movies.size
    }

    fun dedupe(movies: List<TmdbVodCatalog.Movie>): Result {
        if (movies.size <= 1) {
            return Result(movies, movies.size, 0)
        }

        val canonical = mutableListOf<TmdbVodCatalog.Movie>()
        val groups = mutableListOf<MutableList<TmdbVodCatalog.Movie>>()

        for (movie in movies) {
            val matchIndex = groups.indexOfFirst { group ->
                group.any { existing -> matchesAny(existing, movie) }
            }
            if (matchIndex >= 0) {
                groups[matchIndex] += movie
            } else {
                groups += mutableListOf(movie)
            }
        }

        for (group in groups) {
            canonical += mergeGroup(group)
        }

        return Result(
            movies = canonical,
            inputCount = movies.size,
            removedCount = movies.size - canonical.size,
        )
    }

    /** True when [a] and [b] represent the same movie. */
    internal fun matchesAny(a: TmdbVodCatalog.Movie, b: TmdbVodCatalog.Movie): Boolean {
        if (a.tmdbId == b.tmdbId) return true
        val imdbA = a.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val imdbB = b.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (imdbA != null && imdbB != null && imdbA.equals(imdbB, ignoreCase = true)) return true
        return titleYearKey(a) != null && titleYearKey(a) == titleYearKey(b)
    }

    internal fun normalizeTitle(raw: String): String {
        val clean = TmdbVodConfig.cleanListTitle(raw.trim())
        return clean.lowercase()
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\b(the|a|an)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    internal fun titleYearKey(movie: TmdbVodCatalog.Movie): String? {
        val title = normalizeTitle(movie.title).ifBlank { return null }
        val year = movieYear(movie) ?: return null
        return "$title|$year"
    }

    internal fun movieYear(movie: TmdbVodCatalog.Movie): String? =
        VodSort.yearFromReleaseDate(movie.releaseDate)?.toString()
            ?: TmdbVodConfig.parseListTitle(movie.title).year

    internal fun metadataScore(movie: TmdbVodCatalog.Movie): Int {
        var score = 0
        if (!movie.imdbId.isNullOrBlank()) score += 100
        if (!movie.streamQuality.isNullOrBlank()) score += 50
        if (!movie.posterUrl.isNullOrBlank()) score += 30
        if (movie.overview.isNotBlank()) score += 20
        if (movie.voteAverage > 0.0) score += 10
        if (movie.title.isNotBlank()) score += 5
        if (!movie.genre.isNullOrBlank()) score += 5
        if (movie.shelfCategories.isNotEmpty()) score += 5
        return score
    }

    private fun mergeGroup(group: List<TmdbVodCatalog.Movie>): TmdbVodCatalog.Movie {
        if (group.size == 1) return group.first()
        val sorted = group.sortedByDescending { metadataScore(it) }
        var keeper = sorted.first()
        for (other in sorted.drop(1)) {
            keeper = mergeMovies(keeper, other)
        }
        return keeper
    }

    internal fun mergeMovies(
        primary: TmdbVodCatalog.Movie,
        secondary: TmdbVodCatalog.Movie,
    ): TmdbVodCatalog.Movie {
        val best = if (metadataScore(primary) >= metadataScore(secondary)) primary else secondary
        val rest = if (best === primary) secondary else primary
        return best.copy(
            title = best.title.ifBlank { rest.title },
            overview = best.overview.ifBlank { rest.overview },
            releaseDate = best.releaseDate ?: rest.releaseDate,
            posterUrl = best.posterUrl ?: rest.posterUrl,
            imdbId = best.imdbId ?: rest.imdbId,
            streamQuality = best.streamQuality ?: rest.streamQuality,
            voteAverage = best.voteAverage.takeIf { it > 0.0 } ?: rest.voteAverage,
            shelfCategories = mergeShelfCategories(best.shelfCategories, rest.shelfCategories),
            genre = mergeGenres(best.genre, rest.genre),
        )
    }

    internal fun mergeShelfCategories(
        existing: List<String>?,
        incoming: List<String>?,
    ): List<String> {
        val parts = linkedSetOf<String>()
        for (raw in (existing.orEmpty() + incoming.orEmpty())) {
            raw.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        return parts.toList()
    }

    internal fun mergeGenres(a: String?, b: String?): String? {
        val parts = linkedSetOf<String>()
        for (raw in listOf(a, b)) {
            raw?.split("/", "·", "|", ",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { parts += it }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
