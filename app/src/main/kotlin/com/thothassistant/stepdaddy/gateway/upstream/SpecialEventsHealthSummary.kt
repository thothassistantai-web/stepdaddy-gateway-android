package com.thothassistant.stepdaddy.gateway.upstream

/** Operator-facing health labels for Special Events scrape + catalog state. */
object SpecialEventsHealthSummary {
    const val STATUS_DISABLED = "disabled"
    const val STATUS_SYNCING = "syncing"
    const val STATUS_PENDING = "pending"
    const val STATUS_STALE = "stale"
    const val STATUS_EMPTY = "empty"
    const val STATUS_OK = "ok"

    /** Grace after the 15-minute scrape interval before marking stale. */
    private const val STALE_GRACE_MS = 60_000L

    fun status(
        sportsEnabled: Boolean,
        syncInFlight: Boolean,
        guideCount: Int,
        liveEventCount: Int,
        lastSyncMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): String = when {
        !sportsEnabled -> STATUS_DISABLED
        syncInFlight -> STATUS_SYNCING
        lastSyncMs == null || lastSyncMs <= 0L -> STATUS_PENDING
        isStale(lastSyncMs, nowMs) -> STATUS_STALE
        guideCount == 0 && liveEventCount == 0 -> STATUS_EMPTY
        else -> STATUS_OK
    }

    fun isStale(lastSyncMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastSyncMs == null || lastSyncMs <= 0L) return false
        return nowMs - lastSyncMs > SupplementConfig.SPECIAL_EVENTS_SYNC_INTERVAL_MS + STALE_GRACE_MS
    }

    fun ageSeconds(lastSyncMs: Long?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (lastSyncMs == null || lastSyncMs <= 0L) return null
        return (nowMs - lastSyncMs) / 1000
    }
}
