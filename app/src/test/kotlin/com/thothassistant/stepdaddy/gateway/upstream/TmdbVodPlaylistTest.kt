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
                referer = TmdbVodConfig.VIDSRC_REFERER,
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
}
