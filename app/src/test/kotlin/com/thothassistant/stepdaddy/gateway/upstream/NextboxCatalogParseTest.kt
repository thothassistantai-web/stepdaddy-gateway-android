package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextboxCatalogParseTest {
    private val catalog = NextboxCatalog(
        okhttp3.OkHttpClient.Builder().build(),
    )

    @Test
    fun parseMovieSections_extractsTmdbIdTitleYearAndCategory() {
        val html = """
            <h2><span>Popular Movies</span></h2>
            <a href="/movie/1339713/1"><h3>Obsession</h3><span>8.2</span><span>2026</span></a>
            <h2><span>Horror Movies</span></h2>
            <a href="/movie/1083381/1"><h3>Backrooms</h3><span>6.8</span><span>2026</span></a>
        """.trimIndent()
        val rows = catalog.parseMovieSections(html, NextboxConfig.MOVIE_SECTIONS)
        assertTrue(rows.any { it.tmdbId == 1339713 && it.title == "Obsession" && it.category == "Popular Movies" })
        assertTrue(rows.any { it.tmdbId == 1083381 && it.category == "Horror Movies" })
    }

    @Test
    fun parseMovieSections_sameTitleInMultipleSections() {
        val html = """
            <h2><span>Popular Movies</span></h2>
            <a href="/movie/550/1"><h3>Fight Club</h3><span>8.4</span><span>1999</span></a>
            <h2><span>Horror Movies</span></h2>
            <a href="/movie/550/1"><h3>Fight Club</h3><span>8.4</span><span>1999</span></a>
        """.trimIndent()
        val rows = catalog.parseMovieSections(html, NextboxConfig.MOVIE_SECTIONS)
        assertEquals(2, rows.count { it.tmdbId == 550 })
        assertTrue(rows.any { it.tmdbId == 550 && it.category == "Popular Movies" })
        assertTrue(rows.any { it.tmdbId == 550 && it.category == "Horror Movies" })
    }

    @Test
    fun parseShowSections_extractsShowRows() {
        val html = """
            <h2><span>Trending TV Series</span></h2>
            <a href="/tv/125988/1/1/1"><h3>Silo</h3><span>8.2</span><span>2023</span></a>
        """.trimIndent()
        val rows = catalog.parseShowSections(html, NextboxConfig.TV_SECTIONS)
        assertEquals(1, rows.size)
        assertEquals(125988, rows[0].showTmdbId)
        assertEquals("Silo", rows[0].title)
        assertEquals("2023", rows[0].year)
        assertEquals("Trending TV Series", rows[0].category)
    }
}
