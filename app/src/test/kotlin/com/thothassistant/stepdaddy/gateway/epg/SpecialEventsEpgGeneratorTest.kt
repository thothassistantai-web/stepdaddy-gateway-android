package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventSourceMeta
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventsEpgGeneratorTest {
    @Test
    fun streamProgramme_usesParsedEventTitle() {
        val channel = SupplementChannel(
            id = "dlhd-event:abc",
            name = "BERLIN-WTA & NOTTINGHAM-WTA FINAL",
            tvgId = "DLHD.Event.abc123",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventSourceUrl = "Tennis|Sunday 22nd June 2026 - Schedule Time UK GMT|live|Tennis : Berlin-WTA Final",
        )
        val programmes = SpecialEventsEpgGenerator.programmesForBundle(
            channels = listOf(channel),
            guideProgrammes = emptyMap(),
        )
        assertEquals(1, programmes.size)
        assertEquals("Berlin-WTA Final", programmes.single().title)
        assertTrue(programmes.single().stop.isAfter(Instant.now()))
    }

    @Test
    fun endedStream_isOmittedFromEpg() {
        val channel = SupplementChannel(
            id = "dlhd-event:old",
            name = "Old Event",
            tvgId = "DLHD.Event.old",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventSourceUrl = "Tennis|Sunday 21st June 2026 - Schedule Time UK GMT|01:00|Tennis : Finished Match",
        )
        val programmes = SpecialEventsEpgGenerator.programmesForBundle(
            channels = listOf(channel),
            guideProgrammes = emptyMap(),
        )
        assertTrue(programmes.isEmpty())
    }

    @Test
    fun guideProgrammes_skipEndedRows() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val guide = SupplementChannel(
            id = "dlhd-guide:tennis",
            name = "🎾 Tennis Schedule",
            tvgId = "DLHD.Guide.tennis",
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
        )
        val programmes = SpecialEventsEpgGenerator.programmesForBundle(
            channels = listOf(guide),
            guideProgrammes = mapOf(
                guide.id to listOf(
                    SpecialEventsMerger.GuideEventRow(
                        title = "Tennis : Past Match",
                        startMs = now.minus(3, ChronoUnit.HOURS).toEpochMilli(),
                        stopMs = now.minus(1, ChronoUnit.HOURS).toEpochMilli(),
                        category = "Tennis",
                        league = "TENNIS",
                    ),
                    SpecialEventsMerger.GuideEventRow(
                        title = "Tennis : Upcoming Match",
                        startMs = now.plus(1, ChronoUnit.HOURS).toEpochMilli(),
                        stopMs = now.plus(3, ChronoUnit.HOURS).toEpochMilli(),
                        category = "Tennis",
                        league = "TENNIS",
                    ),
                ),
            ),
            now = now,
        )
        assertEquals(1, programmes.size)
        assertEquals("Upcoming Match", programmes.single().title)
        assertFalse(programmes.single().title.contains("Past"))
    }

    @Test
    fun eventSourceMeta_parsesDateKeyWithPipesInMiddle() {
        val meta = DlhdEventSourceMeta.parse(
            "Baseball (MLB)|Sunday 22nd June 2026 - Schedule Time UK GMT|20:00|MLB : Yankees vs Red Sox",
        )
        requireNotNull(meta)
        assertEquals("Baseball (MLB)", meta.category)
        assertEquals("Sunday 22nd June 2026 - Schedule Time UK GMT", meta.dateKey)
        assertEquals("20:00", meta.timeLabel)
        assertEquals("Yankees vs Red Sox", meta.displayTitle())
    }
}
