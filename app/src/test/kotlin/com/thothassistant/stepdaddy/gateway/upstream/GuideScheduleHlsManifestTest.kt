package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideScheduleHlsManifestTest {
    @Test
    fun `manifest wraps mp4 as hls event playlist`() {
        val manifest = GuideScheduleHlsManifest.build("http://127.0.0.1:3000/dlhd-event-guide/nba.mp4")
        assertTrue(manifest.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertTrue(manifest.contains("#EXTINF:120.0,schedule"))
        assertTrue(manifest.contains("http://127.0.0.1:3000/dlhd-event-guide/nba.mp4"))
        assertTrue(manifest.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `tivimate guide streams use hls wrapper not raw mp4`() {
        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = listOf(
                com.thothassistant.stepdaddy.gateway.model.SupplementChannel(
                    id = "dlhd-guide:nba",
                    name = "NBA Schedule",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "",
                    providerTag = "NBA",
                ),
            ),
        )
        assertTrue(playlist.contains("dlhd-event-guide/nba.m3u8|"))
        assertFalse(playlist.contains("dlhd-event-guide/nba.mp4|"))
    }

    @Test
    fun `streamvault guide streams keep progressive mp4 urls`() {
        val playlist = PlaylistBuilder.streamVaultPlaylist(
            channels = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = listOf(
                com.thothassistant.stepdaddy.gateway.model.SupplementChannel(
                    id = "dlhd-guide:nba",
                    name = "NBA Schedule",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "",
                    providerTag = "NBA",
                ),
            ),
        )
        assertTrue(playlist.contains("http://127.0.0.1:3000/dlhd-event-guide/nba.mp4"))
        assertFalse(playlist.contains("dlhd-event-guide/nba.m3u8"))
        assertFalse(playlist.contains("|User-Agent="))
    }
}
