package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Orchestrates special-event catalog lifecycle between upstream syncs:
 * prune ended rows, verify schedule coverage, dedupe by canonical upstream URL.
 */
object EventLifecycleManager {
    data class CatalogState(
        val channels: List<SupplementChannel>,
        val guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
    )

    data class PruneOutcome(
        val state: CatalogState,
        val removedStreams: Int,
        val removedGuides: Int,
        val removedScheduleRows: Int,
        val dedupeRemoved: Int,
    ) {
        val changed: Boolean
            get() = removedStreams > 0 ||
                removedGuides > 0 ||
                removedScheduleRows > 0 ||
                dedupeRemoved > 0
    }

    data class VerifyOutcome(
        val needsRefresh: Boolean,
        val missingLiveStreams: Int,
        val upcomingWithoutStreams: Int,
    )

    /** Result of one periodic maintenance tick — consumed by [SupplementSource]. */
    data class MaintenancePlan(
        val pruned: PruneOutcome,
        val verify: VerifyOutcome,
        val shouldRefresh: Boolean,
        /** Merge fetched rows into existing catalog instead of replacing special rows. */
        val mergeWithExisting: Boolean,
        /** Prune changed catalog but no refresh is due — notify listeners. */
        val notifyCatalogChanged: Boolean,
    )

    fun isSpecialEventChannel(id: String): Boolean =
        SpecialEventCatalogMaintainer.isSpecialEventChannel(id)

    fun pruneExpired(
        state: CatalogState,
        nowMs: Long = System.currentTimeMillis(),
    ): PruneOutcome {
        val pruned = SpecialEventCatalogMaintainer.prune(
            channels = state.channels,
            guideSchedules = state.guideSchedules,
            nowMs = nowMs,
        )
        val deduped = SpecialEventStreamDedup.dedupeChannels(pruned.channels)
        return PruneOutcome(
            state = CatalogState(
                channels = deduped.channels,
                guideSchedules = pruned.guideSchedules,
            ),
            removedStreams = pruned.removedStreams,
            removedGuides = pruned.removedGuides,
            removedScheduleRows = pruned.removedScheduleRows,
            dedupeRemoved = deduped.removedCount,
        )
    }

    fun verifyStarted(
        state: CatalogState,
        nowMs: Long = System.currentTimeMillis(),
        preStartWindowMs: Long = SupplementConfig.SPECIAL_EVENTS_PRE_START_WINDOW_MS,
    ): VerifyOutcome {
        val result = SpecialEventCatalogMaintainer.verifyStartedEvents(
            channels = state.channels,
            guideSchedules = state.guideSchedules,
            nowMs = nowMs,
            preStartWindowMs = preStartWindowMs,
        )
        return VerifyOutcome(
            needsRefresh = result.needsRefresh,
            missingLiveStreams = result.missingLiveStreams,
            upcomingWithoutStreams = result.upcomingWithoutStreams,
        )
    }

    fun dedupeChannels(channels: List<SupplementChannel>): SpecialEventStreamDedup.Result =
        SpecialEventStreamDedup.dedupeChannels(channels)

    fun dedupeBundle(bundle: SpecialEventsMerger.EpgBundle): SpecialEventsMerger.EpgBundle =
        SpecialEventStreamDedup.dedupeBundle(bundle)

    fun mergeFetched(
        existing: CatalogState,
        fetchedChannels: List<SupplementChannel>,
        fetchedGuideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        nowMs: Long = System.currentTimeMillis(),
    ): CatalogState {
        val (mergedChannels, mergedGuides) = SpecialEventCatalogMaintainer.mergeFetchedSpecialEvents(
            existing = existing.channels,
            fetched = fetchedChannels,
            fetchedGuideSchedules = fetchedGuideSchedules,
            existingGuideSchedules = existing.guideSchedules,
            nowMs = nowMs,
        )
        val deduped = SpecialEventStreamDedup.dedupeChannels(mergedChannels)
        return CatalogState(
            channels = deduped.channels,
            guideSchedules = mergedGuides,
        )
    }

    /**
     * Plans the next maintenance action for [SupplementSource.schedulePeriodicSpecialEventsMaintenance].
     */
    fun planMaintenanceTick(
        state: CatalogState,
        lastSpecialEventsSyncMs: Long,
        lastVerifyTriggeredSyncMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): MaintenancePlan {
        val pruned = pruneExpired(state, nowMs)
        val verify = verifyStarted(pruned.state, nowMs)
        val age = nowMs - lastSpecialEventsSyncMs
        val scheduledDue = age >= SupplementConfig.SPECIAL_EVENTS_SYNC_INTERVAL_MS
        val verifyDue = verify.needsRefresh &&
            nowMs - lastVerifyTriggeredSyncMs >= SupplementConfig.SPECIAL_EVENTS_PRUNE_INTERVAL_MS
        val shouldRefresh = scheduledDue || verifyDue
        return MaintenancePlan(
            pruned = pruned,
            verify = verify,
            shouldRefresh = shouldRefresh,
            mergeWithExisting = verifyDue && !scheduledDue,
            notifyCatalogChanged = pruned.changed && !shouldRefresh,
        )
    }
}
