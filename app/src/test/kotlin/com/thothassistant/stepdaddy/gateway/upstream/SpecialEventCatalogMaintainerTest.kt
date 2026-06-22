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
}
