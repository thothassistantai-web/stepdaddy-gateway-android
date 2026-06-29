package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialEventsHealthSummaryTest {
    private val now = 1_700_000_000_000L
    private val freshSync = now - 5 * 60_000L
    private val staleSync = now - SupplementConfig.SPECIAL_EVENTS_SYNC_INTERVAL_MS - 120_000L

    @Test
    fun status_ok_when_fresh_catalog() {
        assertEquals(
            SpecialEventsHealthSummary.STATUS_OK,
            SpecialEventsHealthSummary.status(
                sportsEnabled = true,
                syncInFlight = false,
                guideCount = 3,
                liveEventCount = 12,
                lastSyncMs = freshSync,
                nowMs = now,
            ),
        )
    }

    @Test
    fun status_stale_after_interval_plus_grace() {
        assertEquals(
            SpecialEventsHealthSummary.STATUS_STALE,
            SpecialEventsHealthSummary.status(
                sportsEnabled = true,
                syncInFlight = false,
                guideCount = 2,
                liveEventCount = 1,
                lastSyncMs = staleSync,
                nowMs = now,
            ),
        )
        assertTrue(SpecialEventsHealthSummary.isStale(staleSync, now))
    }

    @Test
    fun status_disabled_when_sports_off() {
        assertEquals(
            SpecialEventsHealthSummary.STATUS_DISABLED,
            SpecialEventsHealthSummary.status(
                sportsEnabled = false,
                syncInFlight = false,
                guideCount = 5,
                liveEventCount = 5,
                lastSyncMs = freshSync,
                nowMs = now,
            ),
        )
    }

    @Test
    fun ageSeconds_computed_from_last_sync() {
        assertEquals(300L, SpecialEventsHealthSummary.ageSeconds(freshSync, now))
        assertFalse(SpecialEventsHealthSummary.isStale(freshSync, now))
    }
}
