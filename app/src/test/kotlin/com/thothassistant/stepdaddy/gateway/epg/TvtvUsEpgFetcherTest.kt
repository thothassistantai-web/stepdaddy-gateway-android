package com.thothassistant.stepdaddy.gateway.epg

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TvtvUsEpgFetcherTest {
    @Test
    fun parseBridgeJson_mapsPlaylistIdToSiteAndXmltvId() {
        val bridge = TvtvUsEpgFetcher.parseBridgeJson(
            """
            {
              "bridge": {
                "LifetimeNetwork.us": {"site_id": "60150", "xmltv_id": "LifetimeNetwork.us"},
                "LifetimeMovieNetwork.us": {"site_id": "18480", "xmltv_id": "LifetimeMovieNetwork.us"},
                " ": {"site_id": "", "xmltv_id": "Bad"}
              }
            }
            """.trimIndent(),
        )
        assertEquals(2, bridge.size)
        assertEquals("60150", bridge["LifetimeNetwork.us"]?.siteId)
        assertEquals("LifetimeNetwork.us", bridge["LifetimeNetwork.us"]?.xmltvId)
        assertEquals("18480", bridge["LifetimeMovieNetwork.us"]?.siteId)
    }

    @Test
    fun gridUrl_usesLineupAndIsoWindow() {
        val start = "2026-06-21T04:00:00Z"
        val end = "2026-06-23T04:00:00Z"
        val url = TvtvUsEpgConfig.gridUrl(start, end, "60150")
        assertEquals(
            "https://www.tvtv.us/api/v1/lineup/USA-NY71652-X/grid/$start/$end/60150",
            url,
        )
    }

    @Test
    fun formatApiInstant_truncatesToSeconds() {
        val instant = Instant.parse("2026-06-21T04:00:00.123Z")
        assertEquals("2026-06-21T04:00:00Z", TvtvUsEpgFetcher.formatApiInstant(instant))
    }
}
