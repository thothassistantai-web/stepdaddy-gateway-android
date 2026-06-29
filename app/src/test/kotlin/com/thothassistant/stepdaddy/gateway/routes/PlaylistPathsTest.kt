package com.thothassistant.stepdaddy.gateway.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPathsTest {
    @Test
    fun `user paths are distinct from diagnostic paths`() {
        val overlap = PlaylistPaths.USER.intersect(PlaylistPaths.DIAGNOSTIC.toSet())
        assertTrue(overlap.isEmpty())
    }

    @Test
    fun `canonical user playlist filenames`() {
        assertEquals("/streamvault.m3u", PlaylistPaths.STREAMVAULT)
        assertEquals("/tivimate.m3u", PlaylistPaths.TIVIMATE)
        assertEquals("/vlc.m3u", PlaylistPaths.VLC)
    }

    @Test
    fun `legacy playlist paths alias canonical user playlists`() {
        assertEquals("/tivimate-setup-playlist.m3u8", PlaylistPaths.TIVIMATE_SETUP)
        assertEquals("/streamvault-setup-playlist.m3u8", PlaylistPaths.STREAMVAULT_SETUP)
        assertEquals("/tivimate-playlist.m3u8", PlaylistPaths.TIVIMATE_LEGACY)
        assertEquals("/streamvault-playlist.m3u8", PlaylistPaths.STREAMVAULT_LEGACY)
        assertTrue(PlaylistPaths.USER.contains(PlaylistPaths.TIVIMATE_LEGACY))
        assertTrue(PlaylistPaths.USER.contains(PlaylistPaths.STREAMVAULT_LEGACY))
    }

    @Test
    fun `m3u8 aliases exist for each user playlist`() {
        assertTrue(PlaylistPaths.USER.contains(PlaylistPaths.STREAMVAULT_M3U8))
        assertTrue(PlaylistPaths.USER.contains(PlaylistPaths.TIVIMATE_M3U8))
        assertTrue(PlaylistPaths.USER.contains(PlaylistPaths.VLC_M3U8))
    }
}
