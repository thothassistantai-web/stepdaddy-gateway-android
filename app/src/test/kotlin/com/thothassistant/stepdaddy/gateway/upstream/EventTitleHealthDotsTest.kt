package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EventTitleHealthDotsTest {
    private fun liveEvent(): Pair<SupplementChannel, Instant> {
        val (start, _) = DlhdScheduleTime.parseWindow("27th June 2026", "14:00")
        return SupplementChannel(
            id = "dlhd-event:abc",
            name = "Final Heat",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "SWIMMING",
            eventSourceUrl = "Swimming|27th June 2026|14:00|Swimming : Final Heat",
            dlhdEventStreamKey = "tv|201",
        ) to start
    }

    @Test
    fun `unknown health shows white dot during live window`() {
        val (event, start) = liveEvent()
        val now = start.plusSeconds(30 * 60)
        assertEquals(
            EventTitleHealthDots.WHITE,
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.UNKNOWN, now),
        )
    }

    @Test
    fun `unhealthy live event shows yellow dot`() {
        val (event, start) = liveEvent()
        val now = start.plusSeconds(30 * 60)
        assertEquals(
            EventTitleHealthDots.YELLOW,
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.UNHEALTHY, now),
        )
    }

    @Test
    fun `healthy live event shows green dot`() {
        val (event, start) = liveEvent()
        val now = start.plusSeconds(30 * 60)
        assertEquals(
            EventTitleHealthDots.GREEN,
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.HEALTHY, now),
        )
    }

    @Test
    fun `ended grace shows red dot regardless of health`() {
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val event = SupplementChannel(
            id = "dlhd-event:grace1",
            name = "Yankees vs Red Sox",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "MLB",
            eventSourceUrl = "Baseball|$dateKey|17:00|MLB : Yankees vs Red Sox",
            dlhdEventStreamKey = "tv|301",
        )
        val now = Instant.parse("2026-06-22T19:10:00Z")
        assertEquals(
            EventTitleHealthDots.RED,
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.HEALTHY, now),
        )
        assertEquals(
            EventTitleHealthDots.RED,
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.UNKNOWN, now),
        )
    }

    @Test
    fun `no prefix before event starts`() {
        val (event, start) = liveEvent()
        val now = start.minusSeconds(30 * 60)
        assertFalse(EventTitleHealthDots.isLiveStarted(event, now))
        assertEquals(
            "",
            EventTitleHealthDots.prefixForSupplement(event, DlhdEventStreamHealth.Status.HEALTHY, now),
        )
    }

    @Test
    fun `non dlhd-event returns empty prefix`() {
        val channel = SupplementChannel(
            id = "sport:1",
            name = "Test",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        assertEquals("", EventTitleHealthDots.prefixForSupplement(channel, DlhdEventStreamHealth.Status.HEALTHY))
    }

    @Test
    fun `pure prefix maps visibility and health`() {
        assertEquals(EventTitleHealthDots.GREEN, EventTitleHealthDots.prefix(
            SpecialEventLifecycle.Visibility.ACTIVE,
            DlhdEventStreamHealth.Status.HEALTHY,
            liveStarted = true,
        ))
        assertEquals(EventTitleHealthDots.RED, EventTitleHealthDots.prefix(
            SpecialEventLifecycle.Visibility.ENDED_GRACE,
            DlhdEventStreamHealth.Status.HEALTHY,
            liveStarted = false,
        ))
        assertEquals("", EventTitleHealthDots.prefix(
            SpecialEventLifecycle.Visibility.EXPIRED,
            DlhdEventStreamHealth.Status.HEALTHY,
            liveStarted = true,
        ))
    }

    @Test
    fun `playlist builder prefixes dlhd-event titles from health store`() {
        val (event, start) = liveEvent()
        val store = DlhdEventStreamHealthStore()
        store.record("abc", DlhdEventStreamHealth.ProbeResult.unhealthy("segment_unreachable"))
        val nowMs = start.plusSeconds(30 * 60).toEpochMilli()
        val playlist = PlaylistBuilder.tivimatePlaylist(
            channels = emptyList<Channel>(),
            baseUrl = "http://127.0.0.1:3000",
            dlhdOrigin = "https://daddylive.org",
            supplements = listOf(event),
            titleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
            nowMs = nowMs,
            eventHealthStore = store,
        )
        assertTrue(playlist.contains("🟡 US: SWIMMING FINAL HEAT ᴸᴵⱽᴱ"))
    }
}
