package com.thothassistant.stepdaddy.gateway.upstream

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

fun dlhdDateKey(offsetDays: Long, zoneId: ZoneId = ZoneId.of("Europe/London")): String {
    val date = LocalDate.now(zoneId).plusDays(offsetDays)
    val day = date.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return "$dayName ${day}$suffix $month ${date.year} - Schedule Time UK GMT"
}
