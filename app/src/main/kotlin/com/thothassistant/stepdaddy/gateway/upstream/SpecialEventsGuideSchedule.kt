package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant

/** Shared schedule rows for guide HTML and video slate renderers. */
object SpecialEventsGuideSchedule {
    data class ViewModel(
        val category: String,
        val emoji: String,
        val statusLine: String,
        val live: List<SpecialEventsMerger.GuideEventRow>,
        val upcoming: List<SpecialEventsMerger.GuideEventRow>,
        val all: List<SpecialEventsMerger.GuideEventRow>,
    )

    fun buildViewModel(
        category: String,
        emoji: String,
        events: List<SpecialEventsMerger.GuideEventRow>,
        nowMs: Long = System.currentTimeMillis(),
        maxRows: Int = 14,
    ): ViewModel {
        val sorted = events.sortedBy { it.startMs }
        val now = Instant.ofEpochMilli(nowMs)
        val live = sorted.filter { row ->
            val start = Instant.ofEpochMilli(row.startMs)
            val stop = Instant.ofEpochMilli(row.stopMs)
            !now.isBefore(start) && now.isBefore(stop)
        }
        val upcoming = sorted.filter { Instant.ofEpochMilli(it.startMs).isAfter(now) }
        val display = buildList {
            addAll(live)
            addAll(upcoming)
        }.take(maxRows)
        val statusLine = buildStatusLine(category, sorted, live, upcoming, now)
        return ViewModel(
            category = category,
            emoji = emoji,
            statusLine = statusLine,
            live = live,
            upcoming = upcoming,
            all = display,
        )
    }

    private fun buildStatusLine(
        category: String,
        all: List<SpecialEventsMerger.GuideEventRow>,
        live: List<SpecialEventsMerger.GuideEventRow>,
        upcoming: List<SpecialEventsMerger.GuideEventRow>,
        now: Instant,
    ): String = when {
        all.isEmpty() -> "No scheduled events for $category right now."
        live.isNotEmpty() -> "${live.size} event${if (live.size == 1) "" else "s"} live now."
        upcoming.isNotEmpty() -> {
            val next = upcoming.first()
            "Next: ${next.title}"
        }
        all.maxByOrNull { it.stopMs }?.let { Instant.ofEpochMilli(it.stopMs).isBefore(now) } == true ->
            "No upcoming events — schedule refreshes regularly."
        else -> "No events on air right now."
    }
}
