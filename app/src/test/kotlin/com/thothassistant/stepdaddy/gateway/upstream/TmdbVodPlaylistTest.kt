package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbVodPlaylistTest {
    @Test
    fun `tivimate playlist includes vod movie rows with poster and proxy url`() {
        val supplements = listOf(
            SupplementChannel(
                id = "vod:tmdb:550",
                name = "Fight Club (1999)",
                tvgId = "tt0137523",
                logo = "https://image.tmdb.org/t/p/w500/poster.jpg",
                groupTitle = TmdbVodConfig.GROUP_TITLE,
                streamUrl = "",
                tags = listOf("#movies", "#vod"),
                providerTag = "TMDB",
                referer = TmdbVodConfig.EMBED_REFERER,
                plot = "An insomniac office worker and a devil-may-care soap maker form an underground fight club.",
                imdbId = "tt0137523",
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.eu",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.LEGACY,
        )

        assertTrue(playlist.contains("group-title=\"🎬 Movies\""))
        assertTrue(playlist.contains("tvg-logo=\"https://image.tmdb.org/t/p/w500/poster.jpg\""))
        assertTrue(playlist.contains("tvg-desc=\"An insomniac office worker"))
        assertTrue(playlist.contains("http://127.0.0.1:3000/vod/movie/550.mp4"))
        assertTrue(playlist.contains("Fight Club (1999)"))
    }

    @Test
    fun `streamvault playlist uses m3u8 vod proxy url`() {
        val supplements = listOf(
            SupplementChannel(
                id = "vod:tmdb:603",
                name = "The Matrix (1999)",
                tvgId = "tt0133093",
                logo = "https://image.tmdb.org/t/p/w500/matrix.jpg",
                groupTitle = TmdbVodConfig.GROUP_TITLE,
                streamUrl = "",
                providerTag = "TMDB",
            ),
        )

        val playlist = PlaylistBuilder.streamVaultPlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.eu",
            supplements = supplements,
        )

        assertTrue(playlist.contains("http://127.0.0.1:3000/vod/movie/603.m3u8"))
    }

    @Test
    fun `tivimate playlist includes vod series rows with episode proxy url`() {
        val supplements = listOf(
            SupplementChannel(
                id = "vod:series:1399:1:1",
                name = "Game of Thrones - S01E01",
                tvgId = "tt0944947",
                logo = "https://image.tmdb.org/t/p/w500/got.jpg",
                groupTitle = TmdbVodConfig.SERIES_GROUP_TITLE,
                streamUrl = "",
                tags = listOf("#series", "#vod", "#shows"),
                providerTag = "VOD",
                referer = TmdbVodConfig.EMBED_REFERER,
                plot = "Lord Eddard Stark is asked to serve as the Hand of the King.",
                imdbId = "tt0944947",
            ),
        )

        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.eu",
            supplements = supplements,
            titleStyle = PlaylistTitleStyle.LEGACY,
        )

        assertTrue(playlist.contains("group-title=\"📺 Shows\""))
        assertTrue(playlist.contains("http://127.0.0.1:3000/vod/series/1399/1/1.mp4"))
        assertTrue(playlist.contains("Game of Thrones - S01E01"))
    }

    @Test
    fun `parseSeriesSupplementId round trips episode key`() {
        val id = TmdbVodConfig.seriesSupplementId(1399, 1, 1)
        val key = TmdbVodConfig.parseSeriesSupplementId(id)
        assertTrue(key != null)
        assertTrue(key!!.showTmdbId == 1399 && key.season == 1 && key.episode == 1)
    }
}
