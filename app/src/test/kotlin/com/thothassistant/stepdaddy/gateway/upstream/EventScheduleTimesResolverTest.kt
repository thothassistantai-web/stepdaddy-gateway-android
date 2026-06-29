package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class EventScheduleTimesResolverTest {
    @Test
    fun fromChannel_prefersStoredEpochMs() {
        val start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        val stop = start.plus(3, ChronoUnit.HOURS)
        val channel = SupplementChannel(
            id = "dlhd-event:stored",
            name = "Stored Event",
            tvgId = "DLHD.Event.stored",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventStartMs = start.toEpochMilli(),
            eventStopMs = stop.toEpochMilli(),
            eventSourceUrl = "Tennis|Sunday 21st June 2026 - Schedule Time UK GMT|01:00|Old Event",
        )
        val schedule = EventScheduleTimesResolver.fromChannel(channel)
        requireNotNull(schedule)
        assertEquals(start, schedule.startInstant())
        assertEquals(stop, schedule.stopInstant())
    }

    @Test
    fun fromChannel_fallsBackToEventSourceUrl() {
        val channel = SupplementChannel(
            id = "dlhd-event:parsed",
            name = "Parsed Event",
            tvgId = "DLHD.Event.parsed",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventSourceUrl = "Tennis|Sunday 22nd June 2026 - Schedule Time UK GMT|18:30|Tennis : Final",
        )
        val schedule = EventScheduleTimesResolver.fromChannel(channel)
        requireNotNull(schedule)
        assertTrue(schedule.stopMs > schedule.startMs)
        assertEquals(18, schedule.startInstant().atZone(java.time.ZoneId.of("Europe/London")).hour)
    }

    @Test
    fun fromChannel_returnsNullWhenNoScheduleData() {
        val channel = SupplementChannel(
            id = "dlhd-event:empty",
            name = "No Schedule",
            tvgId = "DLHD.Event.empty",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
        )
        assertNull(EventScheduleTimesResolver.fromChannel(channel))
    }

    @Test
    fun fromGuideRow_mapsRowWindow() {
        val startMs = Instant.parse("2026-06-22T14:00:00Z").toEpochMilli()
        val stopMs = Instant.parse("2026-06-22T17:00:00Z").toEpochMilli()
        val schedule = EventScheduleTimesResolver.fromGuideRow(
            SpecialEventsMerger.GuideEventRow(
                title = "Tennis : Final",
                startMs = startMs,
                stopMs = stopMs,
                category = "Tennis",
                league = "TENNIS",
            ),
        )
        requireNotNull(schedule)
        assertEquals(startMs, schedule.startMs)
        assertEquals(stopMs, schedule.stopMs)
    }
}
