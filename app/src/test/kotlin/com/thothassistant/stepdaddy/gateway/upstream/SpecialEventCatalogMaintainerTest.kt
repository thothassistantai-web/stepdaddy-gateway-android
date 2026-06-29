package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventCatalogMaintainerTest {
    @Test
    fun `prune removes finished dlhd event stream`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val start = now.minus(5, ChronoUnit.HOURS)
        val stop = now.minus(1, ChronoUnit.HOURS)
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Finished Game",
            tvgId = "DLHD.Event.abc",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "College Baseball|$dateKey|14:00|Game : Team A vs Team B",
        )
        val guide = SupplementChannel(
            id = "dlhd-guide:college-baseball",
            name = "College Baseball Schedule",
            tvgId = "DLHD.Guide.college-baseball",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val result = SpecialEventCatalogMaintainer.prune(
            channels = listOf(guide, event),
            guideSchedules = emptyMap(),
            nowMs = now.toEpochMilli(),
        )
        assertTrue(result.changed)
        assertEquals(1, result.removedStreams)
        assertEquals(0, result.channels.count { it.id.startsWith("dlhd-event:") })
    }

    @Test
    fun `prune keeps ended dlhd event during grace window`() {
        val now = Instant.parse("2026-06-22T19:10:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val event = SupplementChannel(
            id = "dlhd-event:grace",
            name = "Just Finished Game",
            tvgId = "DLHD.Event.grace",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "College Baseball|$dateKey|17:00|Game : Team C vs Team D",
        )
        val result = SpecialEventCatalogMaintainer.prune(
            channels = listOf(event),
            guideSchedules = emptyMap(),
            nowMs = now.toEpochMilli(),
        )
        assertFalse(result.changed)
        assertEquals(1, result.channels.count { it.id == "dlhd-event:grace" })
    }

    @Test
    fun `prune removes guide when streams expired and schedule empty`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val guide = SupplementChannel(
            id = "dlhd-guide:college-baseball",
            name = "College Baseball Schedule",
            tvgId = "DLHD.Guide.college-baseball",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Finished Game",
            tvgId = "DLHD.Event.abc",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "College Baseball|$dateKey|14:00|Game : Team A vs Team B",
        )
        val result = SpecialEventCatalogMaintainer.prune(
            channels = listOf(guide, event),
            guideSchedules = emptyMap(),
            nowMs = now.toEpochMilli(),
        )
        assertTrue(result.changed)
        assertEquals(1, result.removedStreams)
        assertEquals(1, result.removedGuides)
        assertTrue(result.channels.none { SpecialEventCatalogMaintainer.isSpecialEventChannel(it.id) })
    }

    @Test
    fun `prune keeps guide when schedule rows remain active`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val guide = SupplementChannel(
            id = guideId,
            name = "Tennis Schedule",
            tvgId = "DLHD.Guide.tennis",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Wimbledon : Final",
            startMs = now.minus(30, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val result = SpecialEventCatalogMaintainer.prune(
            channels = listOf(guide),
            guideSchedules = mapOf(guideId to listOf(row)),
            nowMs = now.toEpochMilli(),
        )
        assertFalse(result.changed)
        assertEquals(1, result.channels.count { it.id == guideId })
    }

    @Test
    fun `prune drops finished schedule rows`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val guide = SupplementChannel(
            id = guideId,
            name = "Tennis Schedule",
            tvgId = "DLHD.Guide.tennis",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val finished = SpecialEventsMerger.GuideEventRow(
            title = "Wimbledon : Semifinal",
            startMs = now.minus(5, ChronoUnit.HOURS).toEpochMilli(),
            stopMs = now.minus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val live = SpecialEventsMerger.GuideEventRow(
            title = "Wimbledon : Final",
            startMs = now.minus(30, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val result = SpecialEventCatalogMaintainer.prune(
            channels = listOf(guide),
            guideSchedules = mapOf(guideId to listOf(finished, live)),
            nowMs = now.toEpochMilli(),
        )
        assertTrue(result.changed)
        assertEquals(1, result.removedScheduleRows)
        assertEquals(listOf(live), result.guideSchedules[guideId])
    }

    @Test
    fun `verify detects live schedule row missing stream`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Tennis : Live Match",
            startMs = now.minus(10, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val verify = SpecialEventCatalogMaintainer.verifyStartedEvents(
            channels = listOf(
                SupplementChannel(
                    id = guideId,
                    name = "Tennis Schedule",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "",
                ),
            ),
            guideSchedules = mapOf(guideId to listOf(row)),
            nowMs = now.toEpochMilli(),
        )
        assertTrue(verify.needsRefresh)
        assertEquals(1, verify.missingLiveStreams)
        assertEquals(0, verify.upcomingWithoutStreams)
    }

    @Test
    fun `verify detects upcoming row within pre-start window`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:baseball"
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Baseball : Red Sox vs Yankees",
            startMs = now.plus(10, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(4, ChronoUnit.HOURS).toEpochMilli(),
            category = "Baseball",
            league = "MLB",
        )
        val verify = SpecialEventCatalogMaintainer.verifyStartedEvents(
            channels = emptyList(),
            guideSchedules = mapOf(guideId to listOf(row)),
            nowMs = now.toEpochMilli(),
            preStartWindowMs = 15 * 60_000L,
        )
        assertTrue(verify.needsRefresh)
        assertEquals(0, verify.missingLiveStreams)
        assertEquals(1, verify.upcomingWithoutStreams)
    }

    @Test
    fun `verify skips when matching stream exists`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Tennis : Live Match",
            startMs = now.minus(10, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val stream = SupplementChannel(
            id = "dlhd-event:abc123",
            name = "Live Match",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "Tennis|Monday 22nd June 2026 - Schedule Time UK GMT|19:50|Tennis : Live Match",
        )
        val verify = SpecialEventCatalogMaintainer.verifyStartedEvents(
            channels = listOf(stream),
            guideSchedules = mapOf(guideId to listOf(row)),
            nowMs = now.toEpochMilli(),
        )
        assertFalse(verify.needsRefresh)
    }

    @Test
    fun `merge prunes expired existing before applying cap`() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val expired = SupplementChannel(
            id = "dlhd-event:expired",
            name = "Expired Game",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "Brazil|$dateKey|10:00|Brazil : Old Match",
            eventStartMs = now.minus(5, ChronoUnit.HOURS).toEpochMilli(),
            eventStopMs = now.minus(2, ChronoUnit.HOURS).toEpochMilli(),
        )
        val guide = SupplementChannel(
            id = "dlhd-guide:brazil",
            name = "Brazil Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val japanGuide = SupplementChannel(
            id = "dlhd-guide:japan",
            name = "Japan Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val fresh = SupplementChannel(
            id = "dlhd-event:fresh",
            name = "Japan Live",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "Japan|$dateKey|19:00|Japan : Live Match",
            eventStartMs = now.minus(30, ChronoUnit.MINUTES).toEpochMilli(),
            eventStopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
        )
        val (merged, _) = SpecialEventCatalogMaintainer.mergeFetchedSpecialEvents(
            existing = listOf(guide, expired),
            fetched = listOf(japanGuide, fresh),
            fetchedGuideSchedules = emptyMap(),
            existingGuideSchedules = emptyMap(),
            nowMs = now.toEpochMilli(),
        )
        assertTrue(merged.none { it.id == "dlhd-event:expired" })
        assertTrue(merged.any { it.id == "dlhd-event:fresh" })
    }

    @Test
    fun `merge adds fetched stream while keeping existing guide order`() {
        val guide = SupplementChannel(
            id = "dlhd-guide:tennis",
            name = "Tennis Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val existingStream = SupplementChannel(
            id = "dlhd-event:existing",
            name = "Existing Match",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "Tennis|Monday 22nd June 2026 - Schedule Time UK GMT|live|Existing Match",
        )
        val fetchedStream = SupplementChannel(
            id = "dlhd-event:new",
            name = "New Match",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            eventSourceUrl = "Tennis|Monday 22nd June 2026 - Schedule Time UK GMT|live|Tennis : New Match",
        )
        val (merged, guides) = SpecialEventCatalogMaintainer.mergeFetchedSpecialEvents(
            existing = listOf(guide, existingStream),
            fetched = listOf(guide, existingStream, fetchedStream),
            fetchedGuideSchedules = emptyMap(),
            existingGuideSchedules = emptyMap(),
        )
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == "dlhd-event:new" })
        assertTrue(merged.any { it.id == "dlhd-event:existing" })
        assertEquals(0, guides.size)
    }
}
