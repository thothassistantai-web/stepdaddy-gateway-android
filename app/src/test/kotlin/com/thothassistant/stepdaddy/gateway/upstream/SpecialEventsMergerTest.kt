package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventsMergerTest {
    private val futureDateKey = "Monday 6th July 2026 - Schedule Time UK GMT"

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
    fun merge_ordersGuideBlocksAlphabeticallyByDisplayName() {
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "Tennis",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "Tennis : Semifinal",
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
            DaddyLiveEventResolver.ParsedEvent(
                category = "PPV Events",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "PPV Events : Main Card",
                league = "OTHER",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "302",
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
        val ppvGuide = ids.indexOf("dlhd-guide:ppv-events")
        val tennisGuide = ids.indexOf("dlhd-guide:tennis")
        assertTrue(ppvGuide >= 0 && tennisGuide > ppvGuide)
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

    @Test
    fun merge_dedupesDuplicateUpstreamStreamKeys() {
        val sharedStream = DaddyLiveEventResolver.ParsedStream(
            label = "Link - 1",
            channelId = "shared-99",
            source = DaddyLiveEventResolver.StreamSource.TV2,
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Boxing",
                    dateKey = futureDateKey,
                    timeLabel = "live",
                    title = "Boxing : Fight A",
                    league = "BOXING",
                    streams = listOf(sharedStream),
                    live = false,
                ),
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Boxing",
                    dateKey = futureDateKey,
                    timeLabel = "live",
                    title = "Boxing : Fight B",
                    league = "BOXING",
                    streams = listOf(sharedStream),
                    live = false,
                ),
            ),
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 2, streamLinks = 2),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        assertEquals(1, bundle.channels.count { it.id.startsWith("dlhd-event:") })
    }

    @Test
    fun merge_assignsDlhdDotPrefixTvgIds() {
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Tennis",
                    dateKey = futureDateKey,
                    timeLabel = "live",
                    title = "Tennis : Final",
                    league = "TENNIS",
                    streams = listOf(
                        DaddyLiveEventResolver.ParsedStream(
                            label = "Link - 1",
                            channelId = "501",
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
        val guide = bundle.channels.first { it.id.startsWith("dlhd-guide:") }
        val event = bundle.channels.first { it.id.startsWith("dlhd-event:") }
        assertTrue(guide.tvgId!!.startsWith("DLHD.Guide."))
        assertTrue(event.tvgId!!.startsWith("DLHD.Event."))
        assertEquals("DLHD.Guide.tennis", guide.tvgId)
    }

    @Test
    fun merge_storesLanguageCodeFromStreamLabel() {
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "Hockey",
                dateKey = futureDateKey,
                timeLabel = "live",
                title = "NHL : Canadiens vs Maple Leafs",
                league = "NHL",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "TVA Sports",
                        channelId = "401",
                        source = DaddyLiveEventResolver.StreamSource.TV,
                    ),
                ),
                live = false,
            ),
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 1, streamLinks = 1),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        val stream = bundle.channels.first { it.id.startsWith("dlhd-event:") }
        assertEquals("fr", stream.languageCode)
    }

    @Test
    fun merge_storesEventScheduleTimesOnStreams() {
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Tennis",
                    dateKey = futureDateKey,
                    timeLabel = "18:30",
                    title = "Tennis : Final",
                    league = "TENNIS",
                    streams = listOf(
                        DaddyLiveEventResolver.ParsedStream(
                            label = "Link - 1",
                            channelId = "777",
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
        val stream = bundle.channels.first { it.id.startsWith("dlhd-event:") }
        val row = bundle.guideProgrammes.values.flatten().single()
        assertEquals(row.startMs, stream.eventStartMs)
        assertEquals(row.stopMs, stream.eventStopMs)
        assertTrue((stream.eventStopMs ?: 0L) > (stream.eventStartMs ?: 0L))
    }

    @Test
    fun merge_persistsRegionOnTvaSportsStream() {
        val futureDateKey = "Monday 23rd June 2026 - Schedule Time UK GMT"
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Hockey",
                    dateKey = futureDateKey,
                    timeLabel = "live",
                    title = "NHL : Canadiens vs Maple Leafs",
                    league = "NHL",
                    streams = listOf(
                        DaddyLiveEventResolver.ParsedStream(
                            label = "TVA Sports",
                            channelId = "401",
                            source = DaddyLiveEventResolver.StreamSource.TV,
                        ),
                    ),
                    live = true,
                ),
            ),
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 1, streamLinks = 1),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        val stream = bundle.channels.first { it.id.startsWith("dlhd-event:") }
        assertEquals("CA", stream.regionCode)
        assertEquals("fr", stream.languageCode)
    }
}
