package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventsGuideHtmlRendererTest {
    @Test
    fun render_showsEmptyMessageWhenNoEvents() {
        val rendered = SpecialEventsGuideHtmlRenderer.render(
            category = "Swimming",
            emoji = "🏊",
            events = emptyList(),
            baseUrl = "http://127.0.0.1:3000",
        )
        assertTrue(rendered.html.contains("no scheduled events"))
        assertTrue(rendered.html.contains("Swimming"))
    }

    @Test
    fun render_showsUpcomingEventTimes() {
        val start = Instant.now().plus(2, ChronoUnit.HOURS).toEpochMilli()
        val stop = Instant.now().plus(5, ChronoUnit.HOURS).toEpochMilli()
        val rendered = SpecialEventsGuideHtmlRenderer.render(
            category = "Golf",
            emoji = "⛳",
            events = listOf(
                SpecialEventsMerger.GuideEventRow(
                    title = "Golf : Round 1",
                    startMs = start,
                    stopMs = stop,
                    category = "Golf",
                    league = "GOLF",
                ),
            ),
            baseUrl = "http://127.0.0.1:3000",
        )
        assertTrue(rendered.html.contains("Next up"))
        assertTrue(rendered.html.contains("Golf : Round 1"))
        assertTrue(rendered.html.contains("Upcoming"))
        assertTrue(rendered.html.contains("US Eastern"))
    }

    @Test
    fun categoryEmoji_mapsGolfAndSwimming() {
        assertTrue(SpecialEventCategoryEmoji.forCategory("Golf") == "⛳")
        assertTrue(SpecialEventCategoryEmoji.forCategory("Swimming") == "🏊")
    }
}
