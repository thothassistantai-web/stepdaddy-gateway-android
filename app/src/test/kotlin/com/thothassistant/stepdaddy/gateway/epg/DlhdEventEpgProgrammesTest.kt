package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DlhdEventEpgProgrammesTest {
    @Test
    fun programmeForChannel_emitsActiveDlhdEventRow() {
        val now = Instant.parse("2026-06-22T12:00:00Z")
        val start = now.plus(1, ChronoUnit.HOURS)
        val stop = now.plus(4, ChronoUnit.HOURS)
        val channel = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Tennis : Berlin Final",
            tvgId = "DLHD.Event.abc",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            regionCode = "UK",
            languageCode = "en",
        )
        val schedule = EventScheduleTimes.of("", start, stop, EventScheduleSource.DLHD_TV)
        val programme = DlhdEventEpgProgrammes.programmeForChannel(channel, schedule, now)
        requireNotNull(programme)
        assertEquals("DLHD.Event.abc", programme.channelId)
        assertEquals("Berlin Final", programme.title)
        assertEquals(start, programme.start)
        assertEquals(stop, programme.stop)
        assertEquals("UK", programme.regionCode)
        assertEquals("en", programme.languageCode)
    }

    @Test
    fun programmeForChannel_omitsEndedEvents() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val start = now.minus(5, ChronoUnit.HOURS)
        val stop = now.minus(1, ChronoUnit.HOURS)
        val channel = SupplementChannel(
            id = "dlhd-event:old",
            name = "Old Event",
            tvgId = "DLHD.Event.old",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
        )
        val schedule = EventScheduleTimes.of("", start, stop, EventScheduleSource.DLHD_TV)
        assertNull(DlhdEventEpgProgrammes.programmeForChannel(channel, schedule, now))
    }

    @Test
    fun programmesForChannels_filtersNonDlhdEventRows() {
        val now = Instant.parse("2026-06-22T12:00:00Z")
        val start = now.plus(1, ChronoUnit.HOURS)
        val stop = now.plus(3, ChronoUnit.HOURS)
        val event = SupplementChannel(
            id = "dlhd-event:live",
            name = "Live Match",
            tvgId = "DLHD.Event.live",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventStartMs = start.toEpochMilli(),
            eventStopMs = stop.toEpochMilli(),
        )
        val guide = SupplementChannel(
            id = "dlhd-guide:tennis",
            name = "Tennis Schedule",
            tvgId = "DLHD.Guide.tennis",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
        )
        val programmes = DlhdEventEpgProgrammes.programmesForChannels(listOf(guide, event), now)
        assertEquals(1, programmes.size)
        assertTrue(programmes.single().channelId.startsWith("DLHD.Event."))
    }
}
