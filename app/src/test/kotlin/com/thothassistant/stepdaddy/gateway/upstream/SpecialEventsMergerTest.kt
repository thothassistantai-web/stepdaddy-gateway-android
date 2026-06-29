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
    fun merge_ordersGuideBlocksByActiveEventStartNotAlphabet() {
        val dateKey = futureDateKey
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "Zebra Late",
                dateKey = dateKey,
                timeLabel = "live",
                title = "Zebra Late : Match",
                league = "OTHER",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "z1",
                        source = DaddyLiveEventResolver.StreamSource.TV,
                    ),
                ),
                live = true,
            ),
            DaddyLiveEventResolver.ParsedEvent(
                category = "Alpha Early",
                dateKey = dateKey,
                timeLabel = "17:00",
                title = "Alpha Early : Match",
                league = "OTHER",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "a1",
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
        val zebraGuide = ids.indexOf("dlhd-guide:zebra-late")
        val alphaGuide = ids.indexOf("dlhd-guide:alpha-early")
        assertTrue(zebraGuide >= 0 && alphaGuide > zebraGuide)
    }

    @Test
    fun limitStreamLinks_capsAtTwoPerEvent() {
        val streams = (1..5).map { index ->
            DaddyLiveEventResolver.ParsedStream(
                label = "Link - $index",
                channelId = "ch-$index",
                source = DaddyLiveEventResolver.StreamSource.TV,
            )
        }
        val capped = SpecialEventsMerger.limitStreamLinks(streams)
        assertEquals(2, capped.size)
        assertEquals("ch-1", capped[0].channelId)
        assertEquals("ch-2", capped[1].channelId)
    }

    @Test
    fun merge_capsStreamLinksPerEvent() {
        val streams = (1..4).map { index ->
            DaddyLiveEventResolver.ParsedStream(
                label = "Link - $index",
                channelId = "multi-$index",
                source = DaddyLiveEventResolver.StreamSource.TV,
            )
        }
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = listOf(
                DaddyLiveEventResolver.ParsedEvent(
                    category = "Boxing",
                    dateKey = futureDateKey,
                    timeLabel = "live",
                    title = "Boxing : Main Event",
                    league = "BOXING",
                    streams = streams,
                    live = false,
                ),
            ),
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 1, streamLinks = 4),
            theTvAppChannels = emptyList(),
            maxStreams = 10,
        )
        assertEquals(2, bundle.channels.count { it.id.startsWith("dlhd-event:") })
    }

    @Test
    fun merge_prioritizesLiveEventsUnderStreamCap() {
        val dateKey = futureDateKey
        val manyBrazil = (1..30).map { index ->
            DaddyLiveEventResolver.ParsedEvent(
                category = "Brazil",
                dateKey = dateKey,
                timeLabel = "%02d:%02d".format(20 + (index % 3), index % 60),
                title = "Brazil : Match $index",
                league = "SOCCER",
                streams = listOf(
                    DaddyLiveEventResolver.ParsedStream(
                        label = "Link - 1",
                        channelId = "br-$index",
                        source = DaddyLiveEventResolver.StreamSource.TV,
                    ),
                ),
                live = false,
            )
        }
        val japanLive = DaddyLiveEventResolver.ParsedEvent(
            category = "Japan",
            dateKey = dateKey,
            timeLabel = "live",
            title = "Japan : Live Match",
            league = "SOCCER",
            streams = listOf(
                DaddyLiveEventResolver.ParsedStream(
                    label = "Link - 1",
                    channelId = "jp-live",
                    source = DaddyLiveEventResolver.StreamSource.TV,
                ),
            ),
            live = true,
        )
        val bundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = manyBrazil + japanLive,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = 31, streamLinks = 31),
            theTvAppChannels = emptyList(),
            maxStreams = 5,
        )
        val eventStreams = bundle.channels.filter { it.id.startsWith("dlhd-event:") }
        assertTrue(eventStreams.size <= 5)
        assertTrue(eventStreams.any { it.eventSourceUrl?.startsWith("Japan|") == true })
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
