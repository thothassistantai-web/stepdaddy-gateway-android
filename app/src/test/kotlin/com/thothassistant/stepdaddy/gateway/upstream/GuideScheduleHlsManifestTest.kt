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
    fun `manifest does not expose raw mp4 url in playlist m3u`() {
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
        assertTrue(playlist.contains("dlhd-event-guide/nba.mp4|"))
        assertFalse(playlist.contains("dlhd-event-guide/nba.m3u8"))
    }
}
