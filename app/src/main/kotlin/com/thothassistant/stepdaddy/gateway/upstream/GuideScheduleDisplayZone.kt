package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display timezone for Special Events guide schedules (source data is parsed as UK GMT). */
object GuideScheduleDisplayZone {
    val zoneId: ZoneId = ZoneId.of("America/New_York")

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

    val label: String
        get() {
            val abbrev = if (zoneId.rules.isDaylightSavings(Instant.now())) "EDT" else "EST"
            return "Times in US Eastern ($abbrev)"
        }

    fun formatTime(instant: Instant): String =
        instant.atZone(zoneId).format(timeFormatter)

    fun formatWindow(start: Instant, stop: Instant): String =
        "${formatTime(start)} – ${formatTime(stop)}"

    fun formatDateTime(instant: Instant): String =
        instant.atZone(zoneId).format(dateTimeFormatter)

    fun zoned(instant: Instant): ZonedDateTime = instant.atZone(zoneId)
}
