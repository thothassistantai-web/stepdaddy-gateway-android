package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Priority ordering for VOD shelves (Nextbox sections, vsembed latest, Cinemeta genres).
 * Used for catalog cap/backfill and Xtream category ordering.
 */
object VodShelfPriority {
    val MOVIE_PRIORITY_SHELVES = listOf(
        "Popular Movies",
        "Trending Movies",
        "Latest Movies",
        "Movies Airing Lately",
        "Top Rated Movies",
        "Horror Movies",
    )

    val SERIES_PRIORITY_SHELVES = listOf(
        "Trending TV Series",
        "TV Series Airing Today",
        "Latest Shows",
        "Top Rated TV Series",
    )

    private const val BACKFILL_RANK = 1_000

    fun movieShelfRank(shelf: String): Int {
        val normalized = shelf.trim()
        val idx = MOVIE_PRIORITY_SHELVES.indexOfFirst { it.equals(normalized, ignoreCase = true) }
        return if (idx >= 0) idx else BACKFILL_RANK
    }

    fun seriesShelfRank(shelf: String): Int {
        val normalized = shelf.trim()
        val idx = SERIES_PRIORITY_SHELVES.indexOfFirst { it.equals(normalized, ignoreCase = true) }
        return if (idx >= 0) idx else BACKFILL_RANK
    }

    fun bestMovieShelfRank(shelfCategories: List<String>): Int =
        shelfCategories.minOfOrNull { movieShelfRank(it) } ?: BACKFILL_RANK

    fun bestSeriesShelfRank(shelfCategories: List<String>): Int =
        shelfCategories.minOfOrNull { seriesShelfRank(it) } ?: BACKFILL_RANK

    /**
     * Category priority for groupTitle: Nextbox shelf → Latest → Cinemeta genre.
     */
    fun resolveMovieGroupTitle(shelfCategories: List<String>, genre: String?): String {
        val nextboxShelf = shelfCategories
            .sortedBy { movieShelfRank(it) }
            .firstOrNull { shelf ->
                NextboxConfig.MOVIE_SECTIONS.any { it.equals(shelf, ignoreCase = true) }
            }
        if (nextboxShelf != null) {
            return VodCategoryResolver.nextboxMovieGroupTitle(nextboxShelf)
        }
        if (shelfCategories.any { it.equals("Latest Movies", ignoreCase = true) }) {
            return VodCategoryResolver.LATEST_MOVIES
        }
        return VodCategoryResolver.movieGroupTitle(genre)
    }

    fun resolveSeriesGroupTitle(
        shelfCategories: List<String>,
        genre: String?,
        showTitle: String? = null,
        showShelf: Boolean = false,
    ): String {
        val nextboxShelf = shelfCategories
            .sortedBy { seriesShelfRank(it) }
            .firstOrNull { shelf ->
                NextboxConfig.TV_SECTIONS.any { it.equals(shelf, ignoreCase = true) }
            }
        if (nextboxShelf != null) {
            return VodCategoryResolver.nextboxSeriesGroupTitle(nextboxShelf)
        }
        if (shelfCategories.any { it.equals("Latest Shows", ignoreCase = true) }) {
            return VodCategoryResolver.LATEST_SHOWS
        }
        return VodCategoryResolver.seriesGroupTitle(genre, showTitle, showShelf)
    }

    fun shelfGroupTitle(shelf: String, isSeries: Boolean): String =
        if (isSeries) {
            when {
                NextboxConfig.TV_SECTIONS.any { it.equals(shelf, ignoreCase = true) } ->
                    VodCategoryResolver.nextboxSeriesGroupTitle(shelf)
                shelf.equals("Latest Shows", ignoreCase = true) -> VodCategoryResolver.LATEST_SHOWS
                else -> VodCategoryResolver.seriesGroupTitle(shelf)
            }
        } else {
            when {
                NextboxConfig.MOVIE_SECTIONS.any { it.equals(shelf, ignoreCase = true) } ->
                    VodCategoryResolver.nextboxMovieGroupTitle(shelf)
                shelf.equals("Latest Movies", ignoreCase = true) -> VodCategoryResolver.LATEST_MOVIES
                else -> VodCategoryResolver.movieGroupTitle(shelf)
            }
        }

    fun sortMovieCategories(categories: List<String>): List<String> =
        categories.distinct().sortedBy { movieShelfRank(it) }

    fun sortSeriesCategories(categories: List<String>): List<String> =
        categories.distinct().sortedBy { seriesShelfRank(it) }

    fun capMovies(
        movies: List<TmdbVodCatalog.Movie>,
        cap: Int,
    ): List<TmdbVodCatalog.Movie> {
        if (movies.size <= cap) return movies
        val ranked = movies.map { movie ->
            movie to bestMovieShelfRank(movie.shelfCategories)
        }
        val priority = ranked
            .filter { it.second < BACKFILL_RANK }
            .sortedWith(
                compareBy<Pair<TmdbVodCatalog.Movie, Int>> { it.second }
                    .thenByDescending { VodSort.movieSortKey(it.first.releaseDate, it.first.title) },
            )
            .map { it.first }
        val backfill = ranked
            .filter { it.second >= BACKFILL_RANK }
            .sortedByDescending { VodSort.movieSortKey(it.first.releaseDate, it.first.title) }
            .map { it.first }
        return (priority + backfill).take(cap)
    }

    fun capEpisodes(
        episodes: List<TmdbVodSeriesCatalog.Episode>,
        cap: Int,
    ): List<TmdbVodSeriesCatalog.Episode> {
        if (episodes.size <= cap) return episodes
        val ranked = episodes.map { episode ->
            episode to bestSeriesShelfRank(episode.shelfCategories)
        }
        val priority = ranked
            .filter { it.second < BACKFILL_RANK }
            .sortedWith(
                compareBy<Pair<TmdbVodSeriesCatalog.Episode, Int>> { it.second }
                    .thenByDescending { VodSort.movieSortKey(it.first.showYear, it.first.showTitle) }
                    .thenByDescending { it.first.season }
                    .thenByDescending { it.first.episode },
            )
            .map { it.first }
        val backfill = ranked
            .filter { it.second >= BACKFILL_RANK }
            .sortedWith(
                compareByDescending<Pair<TmdbVodSeriesCatalog.Episode, Int>> {
                    VodSort.movieSortKey(it.first.showYear, it.first.showTitle)
                }.thenByDescending { it.first.season }
                    .thenByDescending { it.first.episode },
            )
            .map { it.first }
        return (priority + backfill).take(cap)
    }
}
