package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
    fun eventWindowSortKey_liveBeforeUpcoming() {
        val now = 1_000_000L
        val live = SpecialEventSort.eventWindowSortKey(
            startMs = now - 60_000L,
            stopMs = now + 3_600_000L,
            nowMs = now,
        )
        val upcoming = SpecialEventSort.eventWindowSortKey(
            startMs = now + 3_600_000L,
            stopMs = now + 7_200_000L,
            nowMs = now,
        )
        assertTrue(live < upcoming)
    }

    @Test
    fun eventWindowSortKey_ordersUpcomingByStartTime() {
        val now = 1_000_000L
        val sooner = SpecialEventSort.eventWindowSortKey(
            startMs = now + 600_000L,
            stopMs = now + 3_600_000L,
            nowMs = now,
        )
        val later = SpecialEventSort.eventWindowSortKey(
            startMs = now + 3_600_000L,
            stopMs = now + 7_200_000L,
            nowMs = now,
        )
        assertTrue(sooner < later)
    }

    @Test
    fun guideBlockEventSortKey_prefersEarliestActiveRow() {
        val now = Instant.parse("2026-07-06T18:00:00Z").toEpochMilli()
        val guideId = "dlhd-guide:tennis"
        val programmes = mapOf(
            guideId to listOf(
                SpecialEventsMerger.GuideEventRow(
                    title = "Later",
                    startMs = now + 3_600_000L,
                    stopMs = now + 7_200_000L,
                    category = "Tennis",
                    league = "ATP",
                ),
                SpecialEventsMerger.GuideEventRow(
                    title = "Live Now",
                    startMs = now - 600_000L,
                    stopMs = now + 3_600_000L,
                    category = "Tennis",
                    league = "ATP",
                ),
            ),
        )
        val key = SpecialEventSort.guideBlockEventSortKey(guideId, programmes, now)
        val liveKey = SpecialEventSort.eventWindowSortKey(now - 600_000L, now + 3_600_000L, now)
        assertEquals(liveKey, key)
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

    @Test
    fun guideBlockSortKey_eventSharesGuideDisplayNameKey() {
        val guide = guide("dlhd-guide:baseball-mlb", "⚾ Baseball MLB Schedule", "MLB")
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Yankees vs Red Sox",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "MLB",
            eventSourceUrl = "Baseball MLB|Sunday|19:00|MLB : Yankees vs Red Sox",
        )
        assertEquals(
            SpecialEventSort.guideBlockSortKey(guide),
            SpecialEventSort.guideBlockSortKey(event),
        )
    }

    @Test
    fun guideBlockSortKey_ordersGuidesAlphabeticallyByDisplayName() {
        val ppv = guide("dlhd-guide:ppv-events", "🎟️ PPV Events Schedule", "OTHER")
        val tennis = guide("dlhd-guide:tennis", "🎾 Tennis Schedule", "TENNIS")
        val keys = listOf(ppv, tennis).map { SpecialEventSort.guideBlockSortKey(it) }.sorted()
        assertEquals(
            listOf(
                SpecialEventSort.guideDisplayName(ppv).lowercase(),
                SpecialEventSort.guideDisplayName(tennis).lowercase(),
            ),
            keys,
        )
        assertTrue(keys.first() < keys.last())
    }

    @Test
    fun supplementIntraSlot_placesGuideBeforeEventInBlock() {
        val guide = guide("dlhd-guide:golf", "⛳ Golf Schedule", "GOLF")
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Round 1",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "GOLF",
            eventSourceUrl = "Golf|Sunday|15:00|Golf : Round 1",
        )
        assertTrue(SpecialEventSort.supplementIntraSlot(guide) < SpecialEventSort.supplementIntraSlot(event))
    }

    @Test
    fun guideBlockSortKey_theTvAppRowsSortAfterDlhdBlocks() {
        val guide = guide("dlhd-guide:golf", "⛳ Golf Schedule", "GOLF")
        val tvApp = SupplementChannel(
            id = "sport:nba",
            name = "Lakers vs Celtics",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "https://example.com/nba.m3u8",
            providerTag = "NBA",
        )
        assertTrue(SpecialEventSort.guideBlockSortKey(guide) < SpecialEventSort.guideBlockSortKey(tvApp))
    }

    @Test
    fun guideDisplayName_ordersCategoriesAlphabeticallyIgnoringEmoji() {
        val baseball = guide("dlhd-guide:baseball-mlb", "⚾ Baseball MLB Schedule", "MLB")
        val golf = guide("dlhd-guide:golf", "⛳ Golf Schedule", "GOLF")
        val ppv = guide("dlhd-guide:ppv-events", "🎟️ PPV Events Schedule", "OTHER")
        val tennis = guide("dlhd-guide:tennis", "🎾 Tennis Schedule", "TENNIS")

        val names = listOf(baseball, golf, ppv, tennis)
            .map { SpecialEventSort.guideDisplayName(it).lowercase() }
        assertEquals(names.sorted(), names)
        assertTrue(names.indexOfFirst { it.contains("baseball") } < names.indexOfFirst { it.contains("golf") })
        assertTrue(names.indexOfFirst { it.contains("ppv") } < names.indexOfFirst { it.contains("tennis") })
    }

    @Test
    fun supplementIntraSlot_ordersMultipleEventsAfterGuide() {
        val guide = guide("dlhd-guide:golf", "⛳ Golf Schedule", "GOLF")
        val round1 = SupplementChannel(
            id = "dlhd-event:r1",
            name = "Round 1",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "GOLF",
            eventSourceUrl = "Golf|live|Golf : Round 1",
        )
        val round2 = SupplementChannel(
            id = "dlhd-event:r2",
            name = "Round 2",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "GOLF",
            eventSourceUrl = "Golf|live|Golf : Round 2",
        )
        assertTrue(SpecialEventSort.supplementIntraSlot(guide) < SpecialEventSort.supplementIntraSlot(round1))
        assertTrue(SpecialEventSort.supplementIntraSlot(guide) < SpecialEventSort.supplementIntraSlot(round2))
        assertTrue(SpecialEventSort.supplementIntraSlot(round1) > 0)
        assertTrue(SpecialEventSort.supplementIntraSlot(round2) > 0)
    }

    @Test
    fun guideBlockSortKey_matchesEventCategoryFromEventSourceUrl() {
        val guide = guide("dlhd-guide:baseball-mlb", "⚾ Baseball MLB Schedule", "MLB")
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Yankees vs Red Sox",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "MLB",
            eventSourceUrl = "Baseball MLB|Sunday|19:00|MLB : Yankees vs Red Sox",
        )
        assertEquals(
            SpecialEventSort.guideDisplayName(guide).lowercase(),
            SpecialEventSort.guideBlockSortKey(guide),
        )
        assertEquals(SpecialEventSort.guideBlockSortKey(guide), SpecialEventSort.guideBlockSortKey(event))
    }

    private fun guide(id: String, name: String, league: String): SupplementChannel =
        SupplementChannel(
            id = id,
            name = name,
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = league,
            tags = listOf("#events", "#guide"),
        )
}
