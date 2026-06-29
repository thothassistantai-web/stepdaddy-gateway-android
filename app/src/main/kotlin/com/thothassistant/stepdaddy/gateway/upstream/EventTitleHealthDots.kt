package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.time.Instant

/**
 * Maps dlhd-event schedule visibility + stream health to playlist title prefix emojis.
 *
 * - 🟢 live window, stream healthy
 * - 🔴 post-stop grace (event ended)
 * - 🟡 live window, stream unhealthy
 * - ⚪ live window, health not yet probed
 */
object EventTitleHealthDots {
    const val GREEN = "🟢 "
    const val RED = "🔴 "
    const val YELLOW = "🟡 "
    const val WHITE = "⚪ "

    fun prefix(
        visibility: SpecialEventLifecycle.Visibility,
        healthStatus: DlhdEventStreamHealth.Status,
        liveStarted: Boolean,
    ): String = when (visibility) {
        SpecialEventLifecycle.Visibility.EXPIRED -> ""
        SpecialEventLifecycle.Visibility.ENDED_GRACE -> RED
        SpecialEventLifecycle.Visibility.ACTIVE -> when {
            !liveStarted -> ""
            else -> when (healthStatus) {
                DlhdEventStreamHealth.Status.HEALTHY -> GREEN
                DlhdEventStreamHealth.Status.UNHEALTHY -> YELLOW
                DlhdEventStreamHealth.Status.UNKNOWN -> WHITE
                DlhdEventStreamHealth.Status.ENDED -> RED
            }
        }
    }

    fun prefixForSupplement(
        supplement: SupplementChannel,
        healthStatus: DlhdEventStreamHealth.Status,
        now: Instant = Instant.now(),
    ): String {
        if (!supplement.id.startsWith("dlhd-event:")) return ""
        val visibility = SpecialEventLifecycle.visibilityForDlhdEvent(supplement, now) ?: return ""
        return prefix(visibility, healthStatus, isLiveStarted(supplement, now))
    }

    /** Event has started and is still in its scheduled live window (not ended grace). */
    fun isLiveStarted(
        supplement: SupplementChannel,
        now: Instant = Instant.now(),
    ): Boolean {
        if (!supplement.id.startsWith("dlhd-event:")) return false
        val visibility = SpecialEventLifecycle.visibilityForDlhdEvent(supplement, now) ?: return false
        if (visibility != SpecialEventLifecycle.Visibility.ACTIVE) return false
        val (start, _) = SpecialEventLifecycle.scheduleWindow(supplement) ?: return false
        return !now.isBefore(start)
    }
}
