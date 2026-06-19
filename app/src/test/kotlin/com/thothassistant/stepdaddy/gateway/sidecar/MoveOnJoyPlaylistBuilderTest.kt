package com.thothassistant.stepdaddy.gateway.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveOnJoyPlaylistBuilderTest {
    @Test
    fun `keeps only moveonjoy streams from formatted playlist`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="ESPN (TVApp)",ESPN (TVApp)
            https://thetvapp.link/tv/espn-live-stream/
            #EXTINF:-1 tvg-name="CNN MOJ",CNN MOJ
            http://fl1.moveonjoy.com/CNN/index.m3u8
            #EXTINF:-1 tvg-name="AMC MOJ",AMC MOJ
            http://fl2.moveonjoy.com/AMC_NETWORK/index.m3u8
        """.trimIndent()

        val playlist = MoveOnJoyPlaylistBuilder.fromFormattedPlaylist(raw)

        assertFalse(playlist.contains("thetvapp"))
        assertTrue(playlist.contains("fl1.moveonjoy.com/CNN/index.m3u8"))
        assertTrue(playlist.contains("fl2.moveonjoy.com/AMC_NETWORK/index.m3u8"))
        assertEquals(2, MoveOnJoyPlaylistBuilder.countMoveOnJoyEntries(raw))
    }
}
