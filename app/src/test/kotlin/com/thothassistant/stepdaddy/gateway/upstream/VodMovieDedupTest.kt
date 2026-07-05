package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodMovieDedupTest {
    private fun movie(
        tmdbId: Int,
        title: String,
        year: String? = null,
        imdbId: String? = null,
        overview: String = "",
        posterUrl: String? = null,
        streamQuality: String? = null,
        shelfCategories: List<String> = emptyList(),
        genre: String? = null,
    ) = TmdbVodCatalog.Movie(
        tmdbId = tmdbId,
        title = title,
        releaseDate = year,
        imdbId = imdbId,
        overview = overview,
        posterUrl = posterUrl,
        streamQuality = streamQuality,
        shelfCategories = shelfCategories,
        genre = genre,
    )

    @Test
    fun dedupe_sameTmdbId_collapsesToOne() {
        val input = listOf(
            movie(550, "Fight Club", "1999"),
            movie(550, "Fight Club", "1999", imdbId = "tt0137523"),
        )
        val result = VodMovieDedup.dedupe(input)
        assertEquals(1, result.outputCount)
        assertEquals(1, result.removedCount)
        assertEquals("tt0137523", result.movies.first().imdbId)
    }

    @Test
    fun dedupe_sameTitleYearDifferentCasing_collapsesToOne() {
        val input = listOf(
            movie(1, "Dune", "2021"),
            movie(2, "DUNE", "2021"),
            movie(3, "dune", "2021"),
        )
        val result = VodMovieDedup.dedupe(input)
        assertEquals(1, result.outputCount)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun dedupe_sameImdbIdDifferentTmdbId_keepsPlayableRow() {
        val input = listOf(
            movie(999, "Fight Club", "1999"),
            movie(550, "Fight Club", "1999", imdbId = "tt0137523", streamQuality = "HD"),
        )
        val result = VodMovieDedup.dedupe(input)
        assertEquals(1, result.outputCount)
        assertEquals(550, result.movies.first().tmdbId)
        assertEquals("tt0137523", result.movies.first().imdbId)
    }

    @Test
    fun dedupe_differentTitlesSameYear_notMerged() {
        val input = listOf(
            movie(1, "Dune", "2021"),
            movie(2, "No Time to Die", "2021"),
        )
        val result = VodMovieDedup.dedupe(input)
        assertEquals(2, result.outputCount)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun dedupe_mergesShelfCategoriesFromDuplicates() {
        val input = listOf(
            movie(1, "Alien", "1979", shelfCategories = listOf("Horror Movies")),
            movie(2, "Alien", "1979", shelfCategories = listOf("Popular Movies"), imdbId = "tt0078748"),
        )
        val result = VodMovieDedup.dedupe(input)
        assertEquals(1, result.outputCount)
        assertTrue(result.movies.first().shelfCategories.contains("Horror Movies"))
        assertTrue(result.movies.first().shelfCategories.contains("Popular Movies"))
    }
    @Test
    fun dedupe_prefersRicherMetadata() {
        val sparse = movie(1, "Blade Runner", "1982")
        val rich = movie(
            tmdbId = 2,
            title = "Blade Runner",
            year = "1982",
            imdbId = "tt0083658",
            overview = "A blade runner must pursue replicants.",
            posterUrl = "https://example.com/poster.jpg",
            streamQuality = "HD",
        )
        val result = VodMovieDedup.dedupe(listOf(sparse, rich))
        val kept = result.movies.single()
        assertEquals(2, kept.tmdbId)
        assertEquals("tt0083658", kept.imdbId)
        assertTrue(kept.overview.contains("replicants"))
    }

    @Test
    fun normalizeTitle_stripsArticlesAndPunctuation() {
        assertEquals("fight club", VodMovieDedup.normalizeTitle("The Fight Club"))
        assertEquals("spider man no way home", VodMovieDedup.normalizeTitle("Spider-Man: No Way Home"))
    }

    @Test
    fun dedupe_preservesYearSortOrderAfterDedup() {
        val input = listOf(
            movie(10, "Old Film", "1990"),
            movie(11, "Old Film", "1990", imdbId = "tt0001"),
            movie(20, "New Film", "2024"),
            movie(21, "New Film", "2024", imdbId = "tt0002"),
        )
        val deduped = VodMovieDedup.dedupe(input).movies
        val sorted = deduped.sortedWith(
            compareByDescending<TmdbVodCatalog.Movie> {
                VodSort.movieSortKey(it.releaseDate, it.title)
            },
        )
        assertEquals(listOf(2024, 1990), sorted.map { VodSort.movieSortKey(it.releaseDate, it.title) })
    }
}
