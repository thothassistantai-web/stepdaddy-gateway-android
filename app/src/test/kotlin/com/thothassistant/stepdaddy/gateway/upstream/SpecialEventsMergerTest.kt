package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventsMergerTest {
    private val futureDateKey = "Monday 23rd June 2026 - Schedule Time UK GMT"

    @Test
    fun merge_buildsGuidesStreamsAndDedupsTheTvApp() {
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "PPV Events",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "Baseball : Seattle Mariners vs Boston Red Sox",
                league = "MLB",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "admin/ppv-boston-red-sox-vs-seattle-mariners/1",
                        source = DaddyLiveEventResolver.StreamSource.TV2,
                    ),
                ),
                live = false,
            ),
        )
        val theTvApp = listOf(
            SupplementChannel(
                id = "sport:abc123",
                name = "Seattle Mariners vs Boston Red Sox",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "https://example.com/nba.m3u8",
                providerTag = "MLB",
            ),
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 1, streamLinks = 1),
            theTvAppChannels = theTvApp,
            maxStreams = 10,
        )
        assertTrue(bundle.channels.any { it.id.startsWith("dlhd-guide:") })
        assertTrue(bundle.channels.any { it.id.startsWith("dlhd-event:") })
        assertEquals(0, bundle.channels.count { it.id.startsWith("sport:") })
        assertEquals(1, bundle.guideProgrammes.values.sumOf { it.size })
        val guideIndex = bundle.channels.indexOfFirst { it.id.startsWith("dlhd-guide:") }
        val eventIndex = bundle.channels.indexOfFirst { it.id.startsWith("dlhd-event:") }
        assertTrue(guideIndex >= 0 && eventIndex > guideIndex)
    }

    @Test
    fun merge_interleavesGuideBeforeEachCategoryStreams() {
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "Swimming",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "Swimming : Final Heat",
                league = "SWIMMING",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "201",
                        source = DaddyLiveEventResolver.StreamSource.TV,
                    ),
                ),
                live = false,
            ),
            DaddyLiveEventResolver.ParsedEvent(
                category = "Golf",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "Golf : Round 1",
                league = "GOLF",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "202",
                        source = DaddyLiveEventResolver.StreamSource.TV,
                    ),
                ),
                live = false,
            ),
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 2, streamLinks = 2),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        val ids = bundle.channels.map { it.id }
        val golfGuide = ids.indexOf("dlhd-guide:golf")
        val golfEvent = bundle.channels.indexOfFirst {
            it.id.startsWith("dlhd-event:") && it.eventSourceUrl?.startsWith("Golf|") == true
        }
        val swimGuide = ids.indexOf("dlhd-guide:swimming")
        val swimEvent = bundle.channels.indexOfFirst {
            it.id.startsWith("dlhd-event:") && it.eventSourceUrl?.startsWith("Swimming|") == true
        }
        assertTrue(golfGuide >= 0 && golfEvent > golfGuide)
        assertTrue(swimGuide >= 0 && swimEvent > swimGuide)
        assertTrue(golfGuide < swimGuide)
    }

    @Test
    fun merge_dropsEndedEvents() {
        val past = Instant.now().minus(2, ChronoUnit.HOURS)
        val dateKey = "Sunday 21st June 2026 - Schedule Time UK GMT"
        val timeLabel = "%02d:%02d".format(
            past.atZone(java.time.ZoneId.of("Europe/London")).hour,
            past.atZone(java.time.ZoneId.of("Europe/London")).minute,
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Tennis",
                    dateKey = dateKey,
                    timeLabel = timeLabel,
                    title = "Tennis : Finished Match",
                    league = "TENNIS",
                    streams = listOf(
                        DaddyLiveEventResolver.ParsedStream(
                            label = "Link - 1",
                            channelId = "301",
                            source = DaddyLiveEventResolver.StreamSource.TV,
                        ),
                    ),
                    live = false,
                ),
            ),
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 1, streamLinks = 1),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        assertEquals(0, bundle.channels.count { it.id.startsWith("dlhd-event:") })
        assertEquals(0, bundle.guideProgrammes.values.sumOf { it.size })
    }
}
