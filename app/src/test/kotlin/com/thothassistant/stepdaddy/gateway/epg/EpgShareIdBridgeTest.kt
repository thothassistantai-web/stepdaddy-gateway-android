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

    @Test
    fun expandFromBridge_mapsHbo2EasternHdBeforePacific() {
        val expansion = EpgShareIdBridge.expandFromBridge(
            feedIdsByPlaylistId = mapOf(
                "HBO2.us" to listOf("HBO2.HD.us2", "HBO2.HD.(Pacific).us2"),
            ),
            playlistTvgIds = setOf("HBO2.us"),
        )
        assertTrue(expansion.lookupIds.contains("HBO2.HD.us2"))
        assertTrue(expansion.lookupIds.contains("HBO2.HD.(Pacific).us2"))
        assertEquals(
            "HBO2.us",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "HBO2.HD.us2"),
        )
    }

    @Test
    fun expandFromBridge_mapsLocalCallSignAndMoreMax() {
        val expansion = EpgShareIdBridge.expandFromBridge(
            feedIdsByPlaylistId = mapOf(
                "WATCDT571.us@HD" to listOf("WATC-DT.us_locals1"),
                "MoreMax.us@East" to listOf("MoreMax.HD.us2"),
            ),
            playlistTvgIds = setOf("WATCDT571.us@HD", "MoreMax.us@East"),
        )
        assertEquals(
            "WATCDT571.us@HD",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "WATC-DT.us_locals1"),
        )
        assertEquals(
            "MoreMax.us@East",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "MoreMax.HD.us2"),
        )
    }

    @Test
    fun expandFromBridge_mapsResidual75ExactUs2AndLocals() {
        val expansion = EpgShareIdBridge.expandFromBridge(
            feedIdsByPlaylistId = mapOf(
                "USBD42000250Q" to listOf("American.Crimes.us2"),
                "Freeform.us@East" to listOf("Freeform.HD.us2"),
                "KVTNDT251.us@HD" to listOf("KVTN-DT.us_locals1"),
                "99991622" to listOf("Stories.by.AMC.us2"),
            ),
            playlistTvgIds = setOf(
                "USBD42000250Q",
                "Freeform.us@East",
                "KVTNDT251.us@HD",
                "99991622",
            ),
        )
        assertEquals(
            "USBD42000250Q",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "American.Crimes.us2"),
        )
        assertEquals(
            "Freeform.us@East",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "Freeform.HD.us2"),
        )
        assertEquals(
            "KVTNDT251.us@HD",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "KVTN-DT.us_locals1"),
        )
        assertEquals(
            "99991622",
            EpgTvgIdMatcher.canonicalPlaylistId(expansion, "Stories.by.AMC.us2"),
        )
    }
}
