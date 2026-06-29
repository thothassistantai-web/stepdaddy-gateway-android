package com.thothassistant.stepdaddy.gateway.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.SupplementStatus
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsHealthSummary
import java.util.concurrent.TimeUnit

/** Formats Special Events operator stats from `/health` supplement payload. */
object SpecialEventsDashboardRenderer {
    fun statusLabel(context: Context, supplement: SupplementStatus?): String {
        if (supplement == null || !supplement.sportsEnabled) {
            return context.getString(R.string.special_events_status_disabled)
        }
        return when (supplement.specialEventsStatus) {
            SpecialEventsHealthSummary.STATUS_OK ->
                context.getString(R.string.special_events_status_ok)
            SpecialEventsHealthSummary.STATUS_STALE ->
                context.getString(R.string.special_events_status_stale)
            SpecialEventsHealthSummary.STATUS_SYNCING ->
                context.getString(R.string.special_events_status_syncing)
            SpecialEventsHealthSummary.STATUS_PENDING ->
                context.getString(R.string.special_events_status_pending)
            SpecialEventsHealthSummary.STATUS_EMPTY ->
                context.getString(R.string.special_events_status_empty)
            else -> context.getString(R.string.special_events_status_disabled)
        }
    }

    fun countsLine(context: Context, supplement: SupplementStatus?): String {
        if (supplement == null || !supplement.sportsEnabled) {
            return context.getString(R.string.special_events_counts_disabled)
        }
        return context.getString(
            R.string.special_events_counts_line,
            supplement.specialEventGuides,
            supplement.dlhdEventStreams,
            supplement.sportsEventsScanned,
        )
    }

    fun lastScrapeLine(context: Context, supplement: SupplementStatus?): String {
        if (supplement == null || !supplement.sportsEnabled) {
            return context.getString(R.string.special_events_scrape_disabled)
        }
        val ageSeconds = supplement.specialEventsScrapeAgeSeconds
        if (ageSeconds == null) {
            return context.getString(R.string.special_events_scrape_never)
        }
        return context.getString(
            R.string.special_events_scrape_ago,
            formatAge(context, ageSeconds),
        )
    }

    fun statusColorRes(supplement: SupplementStatus?): Int = when {
        supplement == null || !supplement.sportsEnabled -> R.color.on_background_muted
        supplement.specialEventsStatus == SpecialEventsHealthSummary.STATUS_OK -> R.color.status_ok
        supplement.specialEventsStatus == SpecialEventsHealthSummary.STATUS_STALE -> R.color.status_warn
        supplement.specialEventsStatus == SpecialEventsHealthSummary.STATUS_SYNCING ||
            supplement.specialEventsStatus == SpecialEventsHealthSummary.STATUS_PENDING ->
            R.color.status_warn
        supplement.specialEventsStatus == SpecialEventsHealthSummary.STATUS_EMPTY -> R.color.status_neutral
        else -> R.color.on_background_muted
    }

    fun statusColor(context: Context, supplement: SupplementStatus?): Int =
        ContextCompat.getColor(context, statusColorRes(supplement))

    fun formatAge(context: Context, ageSeconds: Long): String {
        if (ageSeconds < 60) {
            return context.getString(R.string.special_events_age_seconds, ageSeconds)
        }
        val minutes = TimeUnit.SECONDS.toMinutes(ageSeconds)
        if (minutes < 60) {
            return context.getString(R.string.special_events_age_minutes, minutes)
        }
        val hours = TimeUnit.SECONDS.toHours(ageSeconds)
        if (hours < 48) {
            return context.getString(R.string.special_events_age_hours, hours)
        }
        val days = hours / 24
        return context.getString(R.string.special_events_age_days, days)
    }

    fun healthSummaryLine(context: Context, supplement: SupplementStatus?): String {
        if (supplement == null || !supplement.sportsEnabled) return ""
        val status = statusLabel(context, supplement)
        val counts = context.getString(
            R.string.special_events_counts_short,
            supplement.specialEventGuides,
            supplement.dlhdEventStreams,
        )
        val scrape = lastScrapeLine(context, supplement)
        return "$status · $counts · $scrape"
    }

    fun staleWarning(context: Context, supplement: SupplementStatus?): String? {
        if (supplement == null || !supplement.sportsEnabled) return null
        if (supplement.specialEventsStatus != SpecialEventsHealthSummary.STATUS_STALE) return null
        val age = supplement.specialEventsScrapeAgeSeconds ?: return null
        return context.getString(R.string.dashboard_error_special_events_stale, formatAge(context, age))
    }
}
