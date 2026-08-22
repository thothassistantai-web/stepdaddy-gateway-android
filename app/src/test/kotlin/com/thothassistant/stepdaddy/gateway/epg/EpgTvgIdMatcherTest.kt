package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgTvgIdMatcherTest {
    @Test
    fun `expands quality and region suffix variants`() {
        val expansion = EpgTvgIdMatcher.expandWantedIds(setOf("ABCNewsLive.us@SD"))
        assertTrue(expansion.lookupIds.contains("ABCNewsLive.us@SD"))
        assertTrue(expansion.lookupIds.contains("ABCNewsLive.us"))
        assertTrue(expansion.lookupIds.contains("ABCNewsLive.us@US"))
        assertEquals(
            "ABCNewsLive.us@SD",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "ABCNewsLive.us@US"),
        )
    }

    @Test
    fun `maps feed id to playlist id`() {
        val expansion = EpgTvgIdMatcher.expandWantedIds(setOf("48Hours.us@US"))
        assertEquals(
            "48Hours.us@US",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "48Hours.us@SD"),
        )
    }

    @Test
    fun `expands regional suffix variants for epgshare lookup`() {
        val expansion = EpgTvgIdMatcher.expandWantedIds(setOf("PlutoTVHorror.us@Germany"))
        assertTrue(expansion.lookupIds.contains("PlutoTVHorror.us"))
        assertTrue(expansion.lookupIds.contains("PlutoTVHorror.us@Germany"))
        assertEquals(
            "PlutoTVHorror.us@Germany",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "PlutoTVHorror.us"),
        )
    }
}
