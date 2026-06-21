package com.thothassistant.stepdaddy.gateway.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgShareIdBridgeTest {
    @Test
    fun expandFromBridge_mapsFeedIdBackToPlaylistId() {
        val expansion = EpgShareIdBridge.expandFromBridge(
            feedIdsByPlaylistId = mapOf("ESPN.us" to listOf("ESPN.HD.us2")),
            playlistTvgIds = setOf("ESPN.us"),
        )
        assertTrue(expansion.lookupIds.contains("ESPN.HD.us2"))
        assertEquals(
            "ESPN.us",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "ESPN.HD.us2"),
        )
    }
}
