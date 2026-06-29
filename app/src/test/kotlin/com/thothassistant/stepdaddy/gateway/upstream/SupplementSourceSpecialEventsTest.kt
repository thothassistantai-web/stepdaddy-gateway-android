package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering and dedupe contract for special events as [SupplementSource] stores them
 * after [SpecialEventsMerger.buildFromParsed] (see refreshSpecialEventsOnly / mergeSupplements).
 */
class SupplementSourceSpecialEventsTest {
    private val futureDateKey = "Monday 23rd June 2026 - Schedule Time UK GMT"

    private fun dlhdEvent(
        category: String,
        title: String,
        league: String = "OTHER",
        channelId: String = "101",
        source: DaddyLiveEventResolver.StreamSource = DaddyLiveEventResolver.StreamSource.TV,
    ) = DaddyLiveEventResolver.ParsedEvent(
        category = category,
        dateKey = futureDateKey,
        timeLabel = "live",
        title = title,
        league = league,
        streams = listOf(
            DaddyLiveEventResolver.ParsedStream(
                label = "Link - 1",
                channelId = channelId,
                source = source,
            ),
        ),
        live = false,
    )

    /** Simulates SupplementSource.cached special-events slice after merge + dedupe. */
    private fun mergeSpecialEvents(
        dlhdEvents: List<DaddyLiveEventResolver.ParsedEvent>,
        theTvApp: List<SupplementChannel> = emptyList(),
    ): List<SupplementChannel> {
        val raw = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = DaddyLiveEventResolver.ResolveStats(tvEvents = dlhdEvents.size),
            theTvAppChannels = theTvApp,
            maxStreams = 50,
        )
        return SpecialEventStreamDedup.dedupeBundle(raw).channels
    }

    @Test
    fun `guides sort alphabetically by xtream display name`() {
        val channels = mergeSpecialEvents(
            listOf(
                dlhdEvent("Wrestling", "Wrestling : Main Event", channelId = "301"),
                dlhdEvent("Baseball", "Baseball : Game 1", channelId = "302"),
                dlhdEvent("MMA", "MMA : Title Fight", channelId = "303"),
            ),
        )
        val displayNames = channels
            .filter { it.id.startsWith("dlhd-guide:") }
            .map { SpecialEventSort.guideDisplayName(it).lowercase() }
        assertEquals(displayNames.sorted(), displayNames)
    }

    @Test
    fun `each guide is immediately followed by its category streams`() {
        val channels = mergeSpecialEvents(
            listOf(
                dlhdEvent("Golf", "Golf : Round 1", league = "GOLF", channelId = "201"),
                dlhdEvent("Swimming", "Swimming : Final", league = "SWIMMING", channelId = "202"),
            ),
        )
        assertGuideBeforeEvents(channels, "golf")
        assertGuideBeforeEvents(channels, "swimming")
        val golfGuide = channels.indexOfFirst { it.id == "dlhd-guide:golf" }
        val swimGuide = channels.indexOfFirst { it.id == "dlhd-guide:swimming" }
        assertTrue(golfGuide >= 0 && swimGuide > golfGuide)
    }

    @Test
    fun `supplement rows use DLHD dot prefix tvg ids`() {
        val channels = mergeSpecialEvents(
            listOf(dlhdEvent("Tennis", "Tennis : Semifinal", league = "TENNIS", channelId = "401")),
        )
        val guide = channels.first { it.id.startsWith("dlhd-guide:") }
        val event = channels.first { it.id.startsWith("dlhd-event:") }
        assertTrue(guide.tvgId!!.startsWith("DLHD.Guide."))
        assertTrue(event.tvgId!!.startsWith("DLHD.Event."))
        assertEquals("dlhd-guide:tennis", guide.id)
        assertTrue(guide.tvgId!!.endsWith("tennis"))
    }

    @Test
    fun `duplicate upstream stream keys collapse to one supplement row`() {
        val sharedStream = DaddyLiveEventResolver.ParsedStream(
            label = "Link - 1",
            channelId = "shared-99",
            source = DaddyLiveEventResolver.StreamSource.TV2,
        )
        val channels = mergeSpecialEvents(
            listOf(
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
        )
        val events = channels.filter { it.id.startsWith("dlhd-event:") }
        assertEquals(2, events.size)
        assertEquals(1, events.map { it.dlhdEventMirrors.single().streamKey }.toSet().size)
    }

    @Test
    fun `thetvapp sport rows deduped when dlhd already owns normalized title`() {
        val channels = mergeSpecialEvents(
            dlhdEvents = listOf(
                dlhdEvent(
                    category = "MLB",
                    title = "Baseball : Seattle Mariners vs Boston Red Sox",
                    league = "MLB",
                    channelId = "ppv-1",
                    source = DaddyLiveEventResolver.StreamSource.TV2,
                ),
            ),
            theTvApp = listOf(
                SupplementChannel(
                    id = "sport:tvapp1",
                    name = "Seattle Mariners vs Boston Red Sox",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "https://example.com/mlb.m3u8",
                    providerTag = "MLB",
                ),
            ),
        )
        assertEquals(0, channels.count { it.id.startsWith("sport:") })
        assertEquals(1, channels.count { it.id.startsWith("dlhd-event:") })
        assertEquals(1, channels.count { it.id.startsWith("dlhd-guide:") })
    }

    @Test
    fun `supplement channel ids are unique`() {
        val channels = mergeSpecialEvents(
            listOf(
                dlhdEvent("Soccer", "Soccer : Match A", channelId = "501"),
                dlhdEvent("Soccer", "Soccer : Match B", channelId = "502"),
                dlhdEvent("Hockey", "Hockey : Game 1", channelId = "503"),
            ),
        )
        val ids = channels.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `non special supplements stay before merged special events slice`() {
        val iptv = SupplementChannel(
            id = "iptv:fast1",
            name = "Pluto News",
            groupTitle = GroupTitleResolver.NEWS,
            streamUrl = "https://example.com/news.m3u8",
        )
        val special = mergeSpecialEvents(
            listOf(dlhdEvent("Golf", "Golf : Round 2", league = "GOLF", channelId = "601")),
        )
        val cached = listOf(iptv) + special
        val iptvIndex = cached.indexOfFirst { it.id == "iptv:fast1" }
        val guideIndex = cached.indexOfFirst { it.id.startsWith("dlhd-guide:") }
        assertTrue(iptvIndex >= 0 && guideIndex > iptvIndex)
    }

    private fun assertGuideBeforeEvents(channels: List<SupplementChannel>, slug: String) {
        val guideIndex = channels.indexOfFirst { it.id == "dlhd-guide:$slug" }
        val eventIndex = channels.indexOfFirst {
            it.id.startsWith("dlhd-event:") &&
                SpecialEventSort.dlhdCategorySlug(it) == slug
        }
        assertTrue("guide $slug missing", guideIndex >= 0)
        assertTrue("event for $slug missing", eventIndex > guideIndex)
        assertFalse(
            channels.subList(guideIndex + 1, eventIndex).any { it.id.startsWith("dlhd-guide:") },
        )
    }
}
