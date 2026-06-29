package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.time.Instant

/** Keeps Special Events playlist/EPG rows aligned with scheduled start/stop windows. */
object SpecialEventLifecycle {
    enum class Visibility {
        /** Scheduled block has not ended yet (`now < stop`). */
        ACTIVE,

        /** Block ended but still within post-stop grace (playlist only — red-dot title). */
        ENDED_GRACE,

        /** Past grace — remove from catalog and playlists. */
        EXPIRED,
    }

    fun visibility(start: Instant, stop: Instant, now: Instant = Instant.now()): Visibility {
        if (stop.isAfter(now)) return Visibility.ACTIVE
        val graceEnd = stop.plusMillis(SupplementConfig.SPECIAL_EVENT_ENDED_GRACE_MS)
        return if (now.isBefore(graceEnd)) Visibility.ENDED_GRACE else Visibility.EXPIRED
    }

    fun visibility(startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): Visibility =
        visibility(
            start = Instant.ofEpochMilli(startMs),
            stop = Instant.ofEpochMilli(stopMs),
            now = Instant.ofEpochMilli(nowMs),
        )

    /** Scheduled window still open (includes upcoming and on-air rows). */
    fun isActive(start: Instant, stop: Instant, now: Instant = Instant.now()): Boolean =
        stop.isAfter(now)

    fun isActive(startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        stopMs > nowMs

    /** dlhd-event row should remain in supplement cache and playlists. */
    fun isPlaylistVisible(start: Instant, stop: Instant, now: Instant = Instant.now()): Boolean =
        visibility(start, stop, now) != Visibility.EXPIRED

    fun isPlaylistVisible(startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        visibility(startMs, stopMs, nowMs) != Visibility.EXPIRED

    fun isEndedGrace(start: Instant, stop: Instant, now: Instant = Instant.now()): Boolean =
        visibility(start, stop, now) == Visibility.ENDED_GRACE

    fun scheduleWindow(channel: SupplementChannel): Pair<Instant, Instant>? {
        if (!channel.id.startsWith("dlhd-event:")) return null
        val schedule = EventScheduleTimesResolver.fromChannel(channel) ?: return null
        return schedule.startInstant() to schedule.stopInstant()
    }

    fun visibilityForDlhdEvent(
        channel: SupplementChannel,
        now: Instant = Instant.now(),
    ): Visibility? {
        val (start, stop) = scheduleWindow(channel) ?: return null
        return visibility(start, stop, now)
    }

    fun isDlhdEventPlaylistVisible(
        channel: SupplementChannel,
        now: Instant = Instant.now(),
    ): Boolean {
        if (!channel.id.startsWith("dlhd-event:")) return true
        val (start, stop) = scheduleWindow(channel) ?: return true
        return isPlaylistVisible(start, stop, now)
    }
}
