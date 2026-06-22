package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** HTML schedule page for a Special Events guide channel. */
object SpecialEventsGuideHtmlRenderer {
    private val UK = ZoneId.of("Europe/London")
    private val TIME_FMT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    data class RenderedSchedule(
        val html: String,
        val eventCount: Int,
        val upcomingCount: Int,
    )

    fun render(
        category: String,
        emoji: String,
        events: List<SpecialEventsMerger.GuideEventRow>,
        baseUrl: String,
        nowMs: Long = System.currentTimeMillis(),
    ): RenderedSchedule {
        val sorted = events.sortedBy { it.startMs }
        val now = Instant.ofEpochMilli(nowMs)
        val live = sorted.filter { row ->
            val start = Instant.ofEpochMilli(row.startMs)
            val stop = Instant.ofEpochMilli(row.stopMs)
            !now.isBefore(start) && now.isBefore(stop)
        }
        val upcoming = sorted.filter { Instant.ofEpochMilli(it.startMs).isAfter(now) }
        val statusMessage = buildStatusMessage(category, sorted, live, upcoming, now)

        val body = buildString {
            appendLine("""<!DOCTYPE html>""")
            appendLine("""<html lang="en"><head>""")
            appendLine("""<meta charset="utf-8">""")
            appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
            appendLine("""<meta http-equiv="refresh" content="120">""")
            appendLine("""<title>${escapeHtml("$emoji $category Schedule")}</title>""")
            appendLine("""<style>""")
            appendLine(STYLES)
            appendLine("""</style></head><body>""")
            appendLine("""<main>""")
            appendLine("""<header><p class="eyebrow">🎟️ Special Events</p>""")
            appendLine("""<h1>${escapeHtml(emoji)} ${escapeHtml(category)}</h1>""")
            appendLine("""<p class="subtitle">Schedule times UK (GMT/BST)</p></header>""")
            appendLine("""<section class="status">${statusMessage}</section>""")

            if (live.isNotEmpty()) {
                appendLine("""<section><h2>Live now</h2><ul class="events">""")
                live.forEach { appendEventRow(it, "live") }
                appendLine("""</ul></section>""")
            }
            if (upcoming.isNotEmpty()) {
                appendLine("""<section><h2>Upcoming</h2><ul class="events">""")
                upcoming.forEach { appendEventRow(it, "upcoming") }
                appendLine("""</ul></section>""")
            }

            appendLine("""<footer><p>StepDaddy Gateway · ${escapeHtml(baseUrl.trimEnd('/'))}</p>""")
            appendLine("""<p class="hint">Select a stream row below this guide in TiviMate to watch.</p></footer>""")
            appendLine("""</main></body></html>""")
        }
        return RenderedSchedule(
            html = body,
            eventCount = sorted.size,
            upcomingCount = upcoming.size + live.size,
        )
    }

    private fun buildStatusMessage(
        category: String,
        all: List<SpecialEventsMerger.GuideEventRow>,
        live: List<SpecialEventsMerger.GuideEventRow>,
        upcoming: List<SpecialEventsMerger.GuideEventRow>,
        now: Instant,
    ): String {
        if (all.isEmpty()) {
            return """<p class="empty">There are no scheduled events for <strong>${escapeHtml(category)}</strong> right now.</p>"""
        }
        if (live.isNotEmpty()) {
            return """<p class="ok">${live.size} event${if (live.size == 1) "" else "s"} live now.</p>"""
        }
        if (upcoming.isNotEmpty()) {
            val next = upcoming.first()
            val nextStart = Instant.ofEpochMilli(next.startMs).atZone(UK).format(TIME_FMT)
            return """<p class="ok">Next up: <strong>${escapeHtml(next.title)}</strong> at <strong>$nextStart</strong>.</p>"""
        }
        val last = all.maxByOrNull { it.stopMs }
        return if (last != null && Instant.ofEpochMilli(last.stopMs).isBefore(now)) {
            """<p class="empty">No upcoming events for <strong>${escapeHtml(category)}</strong>. The schedule refreshes regularly — check back soon.</p>"""
        } else {
            """<p class="empty">No events on air right now for <strong>${escapeHtml(category)}</strong>.</p>"""
        }
    }

    private fun StringBuilder.appendEventRow(row: SpecialEventsMerger.GuideEventRow, cssClass: String) {
        val start = Instant.ofEpochMilli(row.startMs).atZone(UK)
        val stop = Instant.ofEpochMilli(row.stopMs).atZone(UK)
        append("""<li class="event $cssClass">""")
        append("""<span class="time">${escapeHtml(start.format(TIME_FMT))} – ${escapeHtml(stop.format(TIME_FMT))}</span>""")
        append("""<span class="title">${escapeHtml(row.title)}</span>""")
        append("""<span class="date">${escapeHtml(start.format(DATE_FMT))}</span>""")
        appendLine("</li>")
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private const val STYLES = """
        :root { color-scheme: dark; --bg: #0f1419; --card: #1a2332; --text: #e8eef5; --muted: #8b9cb3; --accent: #5b9fd4; --live: #3dd68c; }
        * { box-sizing: border-box; }
        body { margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: var(--bg); color: var(--text); line-height: 1.45; }
        main { max-width: 720px; margin: 0 auto; padding: 1.25rem 1rem 2rem; }
        header { margin-bottom: 1.25rem; }
        .eyebrow { margin: 0 0 0.35rem; font-size: 0.85rem; color: var(--muted); }
        h1 { margin: 0 0 0.35rem; font-size: 1.65rem; font-weight: 700; }
        .subtitle { margin: 0; color: var(--muted); font-size: 0.95rem; }
        h2 { margin: 1.25rem 0 0.65rem; font-size: 1.05rem; color: var(--accent); text-transform: uppercase; letter-spacing: 0.04em; }
        .status { background: var(--card); border-radius: 10px; padding: 0.85rem 1rem; margin-bottom: 0.5rem; }
        .status p { margin: 0; }
        .status .empty { color: var(--muted); }
        .status .ok { color: var(--text); }
        .events { list-style: none; margin: 0; padding: 0; }
        .event { background: var(--card); border-radius: 10px; padding: 0.85rem 1rem; margin-bottom: 0.55rem; display: grid; gap: 0.2rem; }
        .event.live { border-left: 4px solid var(--live); }
        .event .time { font-size: 0.9rem; color: var(--accent); font-weight: 600; }
        .event .title { font-size: 1rem; }
        .event .date { font-size: 0.82rem; color: var(--muted); }
        footer { margin-top: 1.75rem; padding-top: 1rem; border-top: 1px solid #2a3544; color: var(--muted); font-size: 0.85rem; }
        footer p { margin: 0.35rem 0; }
    """
}
