package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodShelfPriorityTest {
    private fun movie(
        tmdbId: Int,
        title: String,
        year: String? = null,
        shelfCategories: List<String> = emptyList(),
        genre: String? = null,
    ) = TmdbVodCatalog.Movie(
        tmdbId = tmdbId,
        title = title,
        releaseDate = year,
        shelfCategories = shelfCategories,
        genre = genre,
    )

    @Test
    fun movieShelfRank_prioritizesPopularBeforeHorror() {
        assertTrue(VodShelfPriority.movieShelfRank("Popular Movies") <
            VodShelfPriority.movieShelfRank("Horror Movies"))
    }

    @Test
    fun resolveMovieGroupTitle_prefersNextboxShelfOverGenre() {
        val title = VodShelfPriority.resolveMovieGroupTitle(
            shelfCategories = listOf("Horror Movies", "Popular Movies"),
            genre = "Action / Drama",
        )
        assertEquals("🎬 Popular Movies", title)
    }

    @Test
    fun resolveMovieGroupTitle_usesLatestWhenNoNextboxShelf() {
        val title = VodShelfPriority.resolveMovieGroupTitle(
            shelfCategories = listOf("Latest Movies"),
            genre = "Action",
        )
        assertEquals(VodCategoryResolver.LATEST_MOVIES, title)
    }

    @Test
    fun resolveMovieGroupTitle_fallsBackToGenre() {
        val title = VodShelfPriority.resolveMovieGroupTitle(
            shelfCategories = emptyList(),
            genre = "Action / Adventure",
        )
        assertEquals("🎬 Action", title)
    }

    @Test
    fun capMovies_prioritizesPriorityShelvesBeforeBackfill() {
        val movies = listOf(
            movie(1, "Horror Hit", "2024", shelfCategories = listOf("Horror Movies")),
            movie(2, "Popular Hit", "2023", shelfCategories = listOf("Popular Movies")),
            movie(3, "Obscure", "2022", shelfCategories = listOf("Documentary")),
            movie(4, "Trending Hit", "2025", shelfCategories = listOf("Trending Movies")),
        )
        val capped = VodShelfPriority.capMovies(movies, cap = 2)
        assertEquals(listOf(2, 4), capped.map { it.tmdbId })
    }

    @Test
    fun sortMovieCategories_ordersByPriority() {
        val sorted = VodShelfPriority.sortMovieCategories(
            listOf("Horror Movies", "Popular Movies", "Trending Movies"),
        )
        assertEquals(
            listOf("Popular Movies", "Trending Movies", "Horror Movies"),
            sorted,
        )
    }

    @Test
    fun shelfSupplementIds_shareTmdbIdAcrossShelves() {
        val popular = TmdbVodConfig.shelfSupplementId(550, "Popular Movies")
        val horror = TmdbVodConfig.shelfSupplementId(550, "Horror Movies")
        assertEquals("550", TmdbVodConfig.tmdbIdFromSupplementId(popular))
        assertEquals("550", TmdbVodConfig.tmdbIdFromSupplementId(horror))
        assertNotEquals(popular, horror)
        assertTrue(popular.contains("@"))
    }
}
