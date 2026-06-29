package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SpecialEventLifecycleTest {
    @Test
    fun activeWhileStopInFuture() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val start = now.minus(1, ChronoUnit.HOURS)
        val stop = now.plus(2, ChronoUnit.HOURS)
        assertEquals(SpecialEventLifecycle.Visibility.ACTIVE, SpecialEventLifecycle.visibility(start, stop, now))
        assertTrue(SpecialEventLifecycle.isActive(start, stop, now))
        assertTrue(SpecialEventLifecycle.isPlaylistVisible(start, stop, now))
        assertFalse(SpecialEventLifecycle.isEndedGrace(start, stop, now))
    }

    @Test
    fun inactiveAfterStop() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val start = now.minus(4, ChronoUnit.HOURS)
        val stop = now.minus(1, ChronoUnit.HOURS)
        assertFalse(SpecialEventLifecycle.isActive(start, stop, now))
    }

    @Test
    fun endedGraceKeepsPlaylistVisible() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val start = now.minus(4, ChronoUnit.HOURS)
        val stop = now.minus(10, ChronoUnit.MINUTES)
        assertEquals(SpecialEventLifecycle.Visibility.ENDED_GRACE, SpecialEventLifecycle.visibility(start, stop, now))
        assertTrue(SpecialEventLifecycle.isPlaylistVisible(start, stop, now))
        assertTrue(SpecialEventLifecycle.isEndedGrace(start, stop, now))
    }

    @Test
    fun expiredAfterGraceWindow() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val start = now.minus(6, ChronoUnit.HOURS)
        val stop = now.minus(SupplementConfig.SPECIAL_EVENT_ENDED_GRACE_MS + 60_000L, ChronoUnit.MILLIS)
        assertEquals(SpecialEventLifecycle.Visibility.EXPIRED, SpecialEventLifecycle.visibility(start, stop, now))
        assertFalse(SpecialEventLifecycle.isPlaylistVisible(start, stop, now))
        assertFalse(SpecialEventLifecycle.isEndedGrace(start, stop, now))
    }
}
