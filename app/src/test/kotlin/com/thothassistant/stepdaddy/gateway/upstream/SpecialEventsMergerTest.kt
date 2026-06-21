package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventsMergerTest {
    @Test
    fun merge_buildsGuidesStreamsAndDedupsTheTvApp() {
        val dlhdEvents = listOf(
            DaddyLiveEventResolver.ParsedEvent(
                category = "PPV Events",
                dateKey = "Sunday 21st June 2026 - Schedule Time UK GMT",
                timeLabel = "20:00",
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
    }
}
