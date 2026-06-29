package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class EventLifecycleManagerTest {
    @Test
    fun pruneExpired_removesFinishedStreamAndDedupesDuplicates() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val finished = stream(
            id = "dlhd-event:finished",
            name = "Finished Game",
            dlhdEventStreamKey = "tv|done",
            eventSourceUrl = "College Baseball|$dateKey|14:00|Game : Team A vs Team B",
        )
        val live = stream(
            id = "dlhd-event:live-rich",
            name = "Live Game",
            dlhdEventStreamKey = "tv|live",
            eventSourceUrl = "Baseball|$dateKey|19:00|Live Game",
            providerTag = "MLB",
            tvgId = "DLHD.Event.live",
        )
        val liveDup = stream(
            id = "dlhd-event:live-dup",
            name = "Link - 1",
            dlhdEventStreamKey = "tv|live",
        )
        val state = EventLifecycleManager.CatalogState(
            channels = listOf(finished, live, liveDup),
            guideSchedules = emptyMap(),
        )

        val outcome = EventLifecycleManager.pruneExpired(state, now.toEpochMilli())

        assertTrue(outcome.changed)
        assertEquals(1, outcome.removedStreams)
        assertEquals(1, outcome.dedupeRemoved)
        assertEquals(1, outcome.state.channels.count { it.id.startsWith("dlhd-event:") })
        assertEquals("dlhd-event:live-rich", outcome.state.channels.single { it.id.startsWith("dlhd-event:") }.id)
    }

    @Test
    fun verifyStarted_detectsMissingLiveStream() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Tennis : Live Match",
            startMs = now.minus(10, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val state = EventLifecycleManager.CatalogState(
            channels = listOf(
                SupplementChannel(
                    id = guideId,
                    name = "Tennis Schedule",
                    groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                    streamUrl = "",
                ),
            ),
            guideSchedules = mapOf(guideId to listOf(row)),
        )

        val verify = EventLifecycleManager.verifyStarted(state, now.toEpochMilli())

        assertTrue(verify.needsRefresh)
        assertEquals(1, verify.missingLiveStreams)
        assertEquals(0, verify.upcomingWithoutStreams)
    }

    @Test
    fun mergeFetched_dedupesCanonicalUpstreamUrls() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val guide = SupplementChannel(
            id = "dlhd-guide:baseball-mlb",
            name = "Baseball MLB Schedule",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
        )
        val existing = stream(
            id = "dlhd-event:existing",
            name = "Existing Game",
            dlhdEventStreamKey = "tv|shared",
            providerTag = "MLB",
            tvgId = "DLHD.Event.1",
            eventSourceUrl = "Baseball MLB|$dateKey|19:00|Existing Game",
        )
        val fetchedDup = stream(
            id = "dlhd-event:fetched-dup",
            name = "Link - 1",
            dlhdEventStreamKey = "tv|shared",
            eventSourceUrl = "Baseball MLB|$dateKey|19:00|Link - 1",
        )
        val fetchedNew = stream(
            id = "dlhd-event:new",
            name = "New Game",
            dlhdEventStreamKey = "tv|new",
            eventSourceUrl = "Baseball MLB|$dateKey|19:30|New Game",
        )
        val existingState = EventLifecycleManager.CatalogState(
            channels = listOf(guide, existing),
            guideSchedules = emptyMap(),
        )

        val merged = EventLifecycleManager.mergeFetched(
            existing = existingState,
            fetchedChannels = listOf(guide, existing, fetchedDup, fetchedNew),
            fetchedGuideSchedules = emptyMap(),
            nowMs = now.toEpochMilli(),
        )

        assertEquals(3, merged.channels.size)
        assertTrue(merged.channels.any { it.id == "dlhd-event:new" })
        assertTrue(merged.channels.none { it.id == "dlhd-event:fetched-dup" })
    }

    @Test
    fun planMaintenanceTick_scheduledRefreshWhenIntervalElapsed() {
        val now = 1_000_000L
        val plan = EventLifecycleManager.planMaintenanceTick(
            state = EventLifecycleManager.CatalogState(emptyList(), emptyMap()),
            lastSpecialEventsSyncMs = now - SupplementConfig.SPECIAL_EVENTS_SYNC_INTERVAL_MS,
            lastVerifyTriggeredSyncMs = 0L,
            nowMs = now,
        )

        assertTrue(plan.shouldRefresh)
        assertFalse(plan.mergeWithExisting)
        assertFalse(plan.notifyCatalogChanged)
    }

    @Test
    fun planMaintenanceTick_verifyRefreshMergesWithoutFullReplace() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val guideId = "dlhd-guide:tennis"
        val row = SpecialEventsMerger.GuideEventRow(
            title = "Tennis : Live Match",
            startMs = now.minus(5, ChronoUnit.MINUTES).toEpochMilli(),
            stopMs = now.plus(2, ChronoUnit.HOURS).toEpochMilli(),
            category = "Tennis",
            league = "ATP",
        )
        val state = EventLifecycleManager.CatalogState(
            channels = emptyList(),
            guideSchedules = mapOf(guideId to listOf(row)),
        )
        val nowMs = now.toEpochMilli()

        val plan = EventLifecycleManager.planMaintenanceTick(
            state = state,
            lastSpecialEventsSyncMs = nowMs - 60_000L,
            lastVerifyTriggeredSyncMs = 0L,
            nowMs = nowMs,
        )

        assertTrue(plan.shouldRefresh)
        assertTrue(plan.mergeWithExisting)
        assertTrue(plan.verify.needsRefresh)
    }

    @Test
    fun planMaintenanceTick_notifiesWhenPrunedWithoutRefresh() {
        val now = Instant.parse("2026-06-22T20:00:00Z")
        val dateKey = "Monday 22nd June 2026 - Schedule Time UK GMT"
        val finished = stream(
            id = "dlhd-event:finished",
            name = "Finished Game",
            dlhdEventStreamKey = "tv|done",
            eventSourceUrl = "College Baseball|$dateKey|14:00|Game : Team A vs Team B",
        )
        val state = EventLifecycleManager.CatalogState(
            channels = listOf(finished),
            guideSchedules = emptyMap(),
        )
        val nowMs = now.toEpochMilli()

        val plan = EventLifecycleManager.planMaintenanceTick(
            state = state,
            lastSpecialEventsSyncMs = nowMs,
            lastVerifyTriggeredSyncMs = nowMs,
            nowMs = nowMs,
        )

        assertFalse(plan.shouldRefresh)
        assertTrue(plan.notifyCatalogChanged)
        assertTrue(plan.pruned.changed)
    }

    private fun stream(
        id: String,
        name: String,
        dlhdEventStreamKey: String,
        eventSourceUrl: String? = null,
        providerTag: String? = null,
        tvgId: String? = null,
    ): SupplementChannel =
        SupplementChannel(
            id = id,
            name = name,
            tvgId = tvgId,
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = providerTag,
            eventSourceUrl = eventSourceUrl,
            dlhdEventStreamKey = dlhdEventStreamKey,
        )
}
