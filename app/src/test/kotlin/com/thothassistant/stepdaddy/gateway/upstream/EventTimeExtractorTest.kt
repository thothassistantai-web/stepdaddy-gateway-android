package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class EventTimeExtractorTest {
    private val now = Instant.parse("2026-06-21T12:00:00Z")

    @Test
    fun fromDlhdFeeds_tvFixture_parsesScheduledUkWindows() {
        val tv = readResource("/dlhd-tv-sample.json")
        val times = EventTimeExtractor.fromDlhdFeeds(tv, null, now)

        val nascar = times["tv|153"]
        assertNotNull(nascar)
        assertEquals(EventScheduleSource.DLHD_TV, nascar!!.source)
        assertEquals(false, nascar.live)

        val uk = ZoneId.of("Europe/London")
        val expectedStart = java.time.LocalDateTime.of(2026, 6, 21, 20, 0)
            .atZone(uk)
            .toInstant()
        assertEquals(expectedStart, nascar.window().first)
        assertEquals(
            expectedStart.plus(EventTimeExtractor.SCHEDULED_DURATION_HOURS, ChronoUnit.HOURS),
            nascar.window().second,
        )

        val tennis = times["tv|110"]
        assertNotNull(tennis)
        val tennisStart = java.time.LocalDateTime.of(2026, 6, 21, 18, 0)
            .atZone(uk)
            .toInstant()
        assertEquals(tennisStart, tennis!!.window().first)
    }

    @Test
    fun fromDlhdFeeds_tv2Fixture_parsesLiveWindowPerStream() {
        val tv2 = readResource("/dlhd-tv2-sample.json")
        val times = EventTimeExtractor.fromDlhdFeeds(null, tv2, now)

        assertEquals(2, times.size)
        val first = times["tv2|admin/ppv-boston-red-sox-vs-seattle-mariners/1"]
        val second = times["tv2|admin/ppv-boston-red-sox-vs-seattle-mariners/2"]
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(EventScheduleSource.DLHD_TV2, first!!.source)
        assertTrue(first.live)
        assertEquals(now.truncatedTo(ChronoUnit.MINUTES), first.window().first)
        assertEquals(
            now.truncatedTo(ChronoUnit.MINUTES).plus(EventTimeExtractor.LIVE_DURATION_HOURS, ChronoUnit.HOURS),
            first.window().second,
        )
        assertEquals(first.startIso, second!!.startIso)
    }

    @Test
    fun fromDlhdParsedEvent_usesStreamToken() {
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
        val times = EventTimeExtractor.fromDlhdParsedEvent(event, event.streams.single(), now)
        assertEquals("tv|110", times.eventToken)
        assertEquals(EventScheduleSource.DLHD_TV, times.source)
        assertTrue(times.stopMs > times.startMs)
    }

    @Test
    fun fromTheTvAppEventHtml_futureFixture_parsesRelativeStart() {
        val html = readResource("/thetvapp-event-future.html")
        val url = "https://thetvapp.link/world-cup-knockout-stage/canada-south-africa/35600222616"
        val times = EventTimeExtractor.fromTheTvAppEventHtml(html, url, now)

        assertNotNull(times)
        assertEquals(EventScheduleSource.THE_TV_APP, times!!.source)
        assertEquals(false, times.live)
        assertEquals(
            now.plus(15, ChronoUnit.HOURS),
            times.window().first,
        )
        assertEquals(
            now.plus(15, ChronoUnit.HOURS).plus(EventTimeExtractor.SCHEDULED_DURATION_HOURS, ChronoUnit.HOURS),
            times.window().second,
        )
        assertEquals(EventTimeExtractor.theTvAppToken(url), times.eventToken)
    }

    @Test
    fun fromTheTvAppEventHtml_liveFixture_marksInProgress() {
        val html = readResource("/thetvapp-event-live.html")
        val url = "https://thetvapp.link/world-championship-gr-l/panama-ghana/40362886704"
        val times = EventTimeExtractor.fromTheTvAppEventHtml(html, url, now)

        assertNotNull(times)
        assertTrue(times!!.live)
        assertEquals(now.truncatedTo(ChronoUnit.MINUTES), times.window().first)
    }

    @Test
    fun parseTheTvAppBadge_handlesRelativeVariants() {
        val future = EventTimeExtractor.parseTheTvAppBadge("16 hours from now", now)!!
        assertEquals(now.plus(16, ChronoUnit.HOURS), future.first)

        val past = EventTimeExtractor.parseTheTvAppBadge("2 hours ago", now)!!
        assertEquals(now.minus(2, ChronoUnit.HOURS), past.first)
        assertEquals(
            now.minus(2, ChronoUnit.HOURS).plus(EventTimeExtractor.LIVE_DURATION_HOURS, ChronoUnit.HOURS),
            past.second,
        )

        val halftime = EventTimeExtractor.parseTheTvAppBadge("Halftime", now)!!
        assertEquals(now.truncatedTo(ChronoUnit.MINUTES), halftime.first)
    }

    @Test
    fun parseTheTvAppBadge_returnsNullForUnknownLabel() {
        assertNull(EventTimeExtractor.parseTheTvAppBadge("", now))
        assertNull(EventTimeExtractor.parseTheTvAppBadge("TBD", now))
    }

    @Test
    fun eventScheduleTimes_serializesIsoTimestamps() {
        val start = now.plus(1, ChronoUnit.HOURS)
        val stop = start.plus(3, ChronoUnit.HOURS)
        val times = EventScheduleTimes.of(
            eventToken = "tv|153",
            start = start,
            stop = stop,
            source = EventScheduleSource.DLHD_TV,
        )
        assertEquals(start.truncatedTo(ChronoUnit.SECONDS).toString(), times.startIso)
        assertEquals(stop.truncatedTo(ChronoUnit.SECONDS).toString(), times.stopIso)
        assertEquals(start.toEpochMilli(), times.startMs)
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
}
