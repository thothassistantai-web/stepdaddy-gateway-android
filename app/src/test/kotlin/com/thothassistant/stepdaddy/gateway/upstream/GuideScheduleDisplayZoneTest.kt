package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class GuideScheduleDisplayZoneTest {
    @Test
    fun label_mentionsUsEastern() {
        assertTrue(GuideScheduleDisplayZone.label.contains("US Eastern"))
        assertTrue(
            GuideScheduleDisplayZone.label.contains("EDT") ||
                GuideScheduleDisplayZone.label.contains("EST"),
        )
    }

    @Test
    fun formatTime_convertsFromUtcInstantToEastern() {
        // 2026-06-22 02:10 UTC = 2026-06-21 22:10 EDT
        val instant = Instant.parse("2026-06-22T02:10:00Z")
        val formatted = GuideScheduleDisplayZone.formatTime(instant)
        assertEquals("10:10 PM", formatted)
    }

    @Test
    fun zone_isAmericaNewYork() {
        assertEquals("America/New_York", GuideScheduleDisplayZone.zoneId.id)
    }
}
