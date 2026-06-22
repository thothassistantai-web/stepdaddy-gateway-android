package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant

/** Keeps Special Events playlist/EPG rows only while the scheduled block is still current. */
object SpecialEventLifecycle {
    fun isActive(start: Instant, stop: Instant, now: Instant = Instant.now()): Boolean =
        stop.isAfter(now)

    fun isActive(startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        stopMs > nowMs
}
