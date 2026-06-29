package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsMerger
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightEpgBuilderSportsMergeTest {
    @Test
    fun build_mergesSpecialEventProgrammesIntoServedXml() {
        val root = Files.createTempDirectory("epg-sports-merge").toFile()
        val store = EpgStore.forTest(root)
        val sportsFile = File(root, "sports_epg.xml")
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val eventStart = now.plus(1, ChronoUnit.HOURS)
        val eventStop = now.plus(4, ChronoUnit.HOURS)
        val eventTvgId = "DLHD.Event.testmerge"
        val guideTvgId = "DLHD.Guide.tennis"

        val eventChannel = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Tennis : Berlin Final",
            tvgId = eventTvgId,
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
            eventSourceUrl = "Tennis|Sunday 22nd June 2026 - Schedule Time UK GMT|14:00|Tennis : Berlin Final",
            eventStartMs = eventStart.toEpochMilli(),
            eventStopMs = eventStop.toEpochMilli(),
        )
        val guideChannel = SupplementChannel(
            id = "dlhd-guide:tennis",
            name = "🎾 Tennis Schedule",
            tvgId = guideTvgId,
            groupTitle = "🎟️ Special Events",
            streamUrl = "",
        )
        val guideProgrammes = mapOf(
            guideChannel.id to listOf(
                SpecialEventsMerger.GuideEventRow(
                    title = "Tennis : Berlin Final",
                    startMs = eventStart.toEpochMilli(),
                    stopMs = eventStop.toEpochMilli(),
                    category = "Tennis",
                    league = "TENNIS",
                ),
            ),
        )
        SpecialEventsEpgGenerator.writeXml(
            SpecialEventsEpgGenerator.programmesForBundle(
                channels = listOf(guideChannel, eventChannel),
                guideProgrammes = guideProgrammes,
                now = now,
            ),
            sportsFile,
        )

        val builder = LightEpgBuilder(store)
        val result = builder.build(
            tvgIds = emptySet(),
            sportsEpgFile = sportsFile,
            sportsTvgIds = setOf(eventTvgId, guideTvgId),
            placeholdersEnabled = false,
        )
        val xml = result.outputFile.readText()

        assertTrue("event channel block present", xml.contains("""channel="$eventTvgId""""))
        assertTrue("guide channel block present", xml.contains("""channel="$guideTvgId""""))
        assertTrue("event title present", xml.contains("<title>Berlin Final</title>"))
        assertTrue("guide title present", xml.contains("<title>Berlin Final</title>"))
        assertEquals(2, result.programmeCount)
        assertTrue(eventTvgId in result.channelIdsWithProgrammes)
        assertTrue(guideTvgId in result.channelIdsWithProgrammes)

        root.deleteRecursively()
    }
}
