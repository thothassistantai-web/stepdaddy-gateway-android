package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbVodSourceTest {
    @Test
    fun `vod supplement channel shape`() {
        val movie = TmdbVodCatalog.Movie(
            tmdbId = 550,
            title = "Fight Club",
            overview = "An insomniac office worker...",
            releaseDate = "1999-10-15",
            voteAverage = 8.4,
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            imdbId = "tt0137523",
        )
        val channel = SupplementChannel(
            id = TmdbVodConfig.supplementId(movie.tmdbId),
            name = TmdbVodConfig.displayTitle(movie.title, movie.releaseDate),
            tvgId = movie.imdbId,
            logo = movie.posterUrl,
            groupTitle = TmdbVodConfig.GROUP_TITLE,
            streamUrl = "",
            tags = listOf("#movies", "#vod"),
            providerTag = TmdbVodConfig.PROVIDER_TAG,
            referer = TmdbVodConfig.VIDSRC_REFERER,
            plot = movie.overview,
            imdbId = movie.imdbId,
        )

        assertEquals("vod:tmdb:550", channel.id)
        assertEquals("Fight Club (1999)", channel.name)
        assertEquals("tt0137523", channel.tvgId)
        assertEquals(TmdbVodConfig.GROUP_TITLE, channel.groupTitle)
        assertTrue(channel.plot!!.contains("insomniac"))
    }

    @Test
    fun `displayTitle formats year`() {
        assertEquals("Dune (2021)", TmdbVodConfig.displayTitle("Dune", "2021-10-22"))
        assertEquals("Dune", TmdbVodConfig.displayTitle("Dune", null))
    }

    @Test
    fun `supplementId and parse round trip`() {
        assertEquals("vod:tmdb:42", TmdbVodConfig.supplementId(42))
        assertEquals("42", TmdbVodConfig.tmdbIdFromSupplementId("vod:tmdb:42"))
    }
}
