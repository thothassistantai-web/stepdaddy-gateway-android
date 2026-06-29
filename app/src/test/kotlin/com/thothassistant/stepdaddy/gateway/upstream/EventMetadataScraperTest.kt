package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventMetadataSource
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMetadataScraperTest {
    @Test
    fun fromDlhdParsedEvent_extractsUkRegionAndSportType() {
        val event = DaddyLiveEventResolver.ParsedEvent(
            category = "Tennis",
            dateKey = "Sunday 21st June 2026 - Schedule Time UK GMT",
            timeLabel = "18:00",
            title = "Wimbledon Centre Court",
            league = "TENNIS",
            streams = listOf(
                DaddyLiveEventResolver.ParsedStream(
                    label = "Tennis Stream",
                    channelId = "110",
                    source = DaddyLiveEventResolver.StreamSource.TV,
                ),
            ),
            live = false,
        )
        val meta = EventMetadataScraper.fromDlhdParsedEvent(event, "dlhd-event:abc123")
        assertEquals("Wimbledon Centre Court", meta.title)
        assertEquals("UK", meta.region)
        assertEquals("TENNIS", meta.league)
        assertEquals("Tennis", meta.sportType)
        assertEquals(EventMetadataSource.DLHD_TV, meta.source)
        assertEquals("wimbledon-centre-court", meta.slug)
    }

    @Test
    fun fromDlhdParsedEvent_tv2SourceAndCanadianRegion() {
        val event = DaddyLiveEventResolver.ParsedEvent(
            category = "Canadian Hockey",
            dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT",
            timeLabel = "live",
            title = "NHL: Maple Leafs vs Canadiens",
            league = "NHL",
            streams = listOf(
                DaddyLiveEventResolver.ParsedStream(
                    label = "TVA Sports",
                    channelId = "admin/nhl/1",
                    source = DaddyLiveEventResolver.StreamSource.TV2,
                ),
            ),
            live = true,
        )
        val meta = EventMetadataScraper.fromDlhdParsedEvent(
            event,
            channelId = "dlhd-event:tv2hash",
            streamLabel = "TVA Sports",
        )
        assertEquals(EventMetadataSource.DLHD_TV2, meta.source)
        assertEquals("CA", meta.region)
        assertEquals("Ice Hockey", meta.sportType)
        assertEquals("fr", meta.languageCode)
    }

    @Test
    fun fromTheTvAppChannel_parsesSlugAndLeague() {
        val channel = SupplementChannel(
            id = "sport:deadbeef",
            name = "San Antonio Spurs vs New York Knicks",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "https://cdn.example/live.m3u8",
            providerTag = "NBA",
            eventSourceUrl = "https://thetvapp.link/nba/san-antonio-spurs-new-york-knicks/43353157680",
        )
        val meta = EventMetadataScraper.fromSupplementChannel(channel)
        assertNotNull(meta)
        assertEquals("nba/san-antonio-spurs-new-york-knicks", meta!!.slug)
        assertEquals("NBA", meta.league)
        assertEquals("Basketball", meta.sportType)
        assertEquals(EventMetadataSource.THE_TV_APP, meta.source)
        assertEquals("US", meta.region)
    }

    @Test
    fun fromDlhdGuideChannel_usesCategorySlug() {
        val channel = SupplementChannel(
            id = "dlhd-guide:golf",
            name = "⛳ Golf Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "GOLF",
        )
        val meta = EventMetadataScraper.fromSupplementChannel(channel)
        assertNotNull(meta)
        assertEquals("golf", meta!!.slug)
        assertEquals("Golf", meta.sportType)
    }

    @Test
    fun scrapeChannels_indexesAllSpecialEventRows() {
        val channels = listOf(
            SupplementChannel(
                id = "dlhd-guide:tennis",
                name = "🎾 Tennis Schedule",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "TENNIS",
            ),
            SupplementChannel(
                id = "dlhd-event:abc",
                name = "Berlin-WTA Final",
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                streamUrl = "",
                providerTag = "TENNIS",
                dlhdEventStreamKey = "tv|110",
                eventSourceUrl = "Tennis|Sunday 22nd June 2026 - Schedule Time UK GMT|live|Tennis : Berlin-WTA Final",
            ),
        )
        val scraped = EventMetadataScraper.scrapeChannels(channels)
        assertEquals(2, scraped.size)
        assertTrue(scraped.containsKey("dlhd-guide:tennis"))
        assertEquals("UK", scraped.getValue("dlhd-event:abc").region)
    }

    @Test
    fun sportTypeFor_mapsLeagues() {
        assertEquals("American Football", EventMetadataScraper.sportTypeFor("NFL", "", ""))
        assertEquals("Soccer", EventMetadataScraper.sportTypeFor("MLS", "Soccer", ""))
        assertEquals("Motorsport", EventMetadataScraper.sportTypeFor("NASCAR", "", "NASCAR Cup Series"))
    }
}
