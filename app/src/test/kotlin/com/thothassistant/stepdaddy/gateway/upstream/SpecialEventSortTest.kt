package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventSortTest {
    @Test
    fun leagueFromEventUrl_parsesSlug() {
        assertEquals(
            "NBA",
            SpecialEventSort.leagueFromEventUrl(
                "https://thetvapp.link/nba/san-antonio-spurs-new-york-knicks/43353157680",
            ),
        )
        assertEquals(
            "MLB",
            SpecialEventSort.leagueFromEventUrl(
                "https://thetvapp.link/mlb/boston-red-sox-toronto-blue-jays/43112409480",
            ),
        )
    }

    @Test
    fun sortKey_ordersNflBeforeNba() {
        val nfl = SpecialEventSort.sortKey("NFL", "Chiefs vs Bills")
        val nba = SpecialEventSort.sortKey("NBA", "Lakers vs Celtics")
        assertTrue(nfl < nba)
    }

    @Test
    fun sortKey_fallsBackToEventUrl() {
        val key = SpecialEventSort.sortKey(
            providerTag = null,
            channelName = "Spurs vs Knicks",
            eventUrl = "https://thetvapp.link/nba/spurs-knicks/1",
        )
        val nbaBaseline = SpecialEventSort.sortKey("NBA", "Spurs vs Knicks")
        assertEquals(nbaBaseline / 10_000, key / 10_000)
    }
}
