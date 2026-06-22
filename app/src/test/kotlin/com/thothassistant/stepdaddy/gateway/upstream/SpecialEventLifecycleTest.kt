package com.thothassistant.stepdaddy.gateway.upstream

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
        assertTrue(SpecialEventLifecycle.isActive(start, stop, now))
    }

    @Test
    fun inactiveAfterStop() {
        val now = Instant.parse("2026-06-22T08:00:00Z")
        val start = now.minus(4, ChronoUnit.HOURS)
        val stop = now.minus(1, ChronoUnit.HOURS)
        assertFalse(SpecialEventLifecycle.isActive(start, stop, now))
    }
}
