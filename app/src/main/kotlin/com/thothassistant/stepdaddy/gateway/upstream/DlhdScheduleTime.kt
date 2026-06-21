package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Parses UK-GMT schedule labels from DaddyLive `tv.json` / `tv2.json`. */
object DlhdScheduleTime {
    private val UK = ZoneId.of("Europe/London")
    private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

    private val dateKeyPattern = Regex(
        """(\d{1,2})(?:st|nd|rd|th)?\s+(\w+)\s+(\d{4})""",
        RegexOption.IGNORE_CASE,
    )
    private val clockPattern = Regex("""^(\d{1,2}):(\d{2})$""")

    fun parseWindow(
        dateKey: String,
        timeLabel: String,
        liveHours: Long = 4L,
    ): Pair<Instant, Instant> {
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val label = timeLabel.trim()
        if (label.equals("live", ignoreCase = true)) {
            return now to now.plus(liveHours, ChronoUnit.HOURS)
        }
        val start = parseScheduledStart(dateKey, label) ?: return now to now.plus(liveHours, ChronoUnit.HOURS)
        val stop = start.plus(3, ChronoUnit.HOURS)
        return start to stop
    }

    fun parseScheduledStart(dateKey: String, timeLabel: String): Instant? {
        val match = clockPattern.matchEntire(timeLabel.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val date = parseDateKey(dateKey) ?: return null
        val local = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
        return local.atZone(UK).toInstant()
    }

    private fun parseDateKey(dateKey: String): LocalDate? {
        val match = dateKeyPattern.find(dateKey) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val monthName = match.groupValues[2]
        val year = match.groupValues[3].toIntOrNull() ?: return null
        val month = runCatching {
            LocalDate.parse("1 $monthName $year", DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)).monthValue
        }.getOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
}
