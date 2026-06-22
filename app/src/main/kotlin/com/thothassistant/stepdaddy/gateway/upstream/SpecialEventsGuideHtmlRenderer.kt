package com.thothassistant.stepdaddy.gateway.upstream

import java.time.Instant

/** HTML schedule page for a Special Events guide channel. */
object SpecialEventsGuideHtmlRenderer {
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
        val theme = SpecialEventsGuideTheme.forCategory(category, emoji)
        val themeCss = themeCss(theme)

        val body = buildString {
            appendLine("""<!DOCTYPE html>""")
            appendLine("""<html lang="en"><head>""")
            appendLine("""<meta charset="utf-8">""")
            appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
            appendLine("""<meta http-equiv="refresh" content="120">""")
            appendLine("""<title>${escapeHtml("$emoji $category Schedule")}</title>""")
            appendLine("""<style>""")
            appendLine(BASE_STYLES)
            appendLine(themeCss)
            appendLine("""</style></head><body>""")
            appendLine("""<div class="watermark" aria-hidden="true">${theme.watermarkEmoji}</div>""")
            appendLine("""<main>""")
            appendLine("""<header><p class="eyebrow">🎟️ Special Events</p>""")
            appendLine("""<h1>${escapeHtml(emoji)} ${escapeHtml(category)}</h1>""")
            appendLine("""<p class="subtitle">${escapeHtml(GuideScheduleDisplayZone.label)}</p></header>""")
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
            val nextStart = GuideScheduleDisplayZone.formatDateTime(Instant.ofEpochMilli(next.startMs))
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
        val start = Instant.ofEpochMilli(row.startMs)
        val stop = Instant.ofEpochMilli(row.stopMs)
        append("""<li class="event $cssClass">""")
        append("""<span class="time">${escapeHtml(GuideScheduleDisplayZone.formatWindow(start, stop))}</span>""")
        append("""<span class="title">${escapeHtml(row.title)}</span>""")
        append("""<span class="date">${escapeHtml(GuideScheduleDisplayZone.formatDateTime(start))}</span>""")
        appendLine("</li>")
    }

    private fun themeCss(theme: SpecialEventsGuideTheme): String {
        fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
        return """
            :root {
              --bg-top: ${hex(theme.gradientTop)};
              --bg-bottom: ${hex(theme.gradientBottom)};
              --accent: ${hex(theme.accent)};
              --accent-soft: ${hex(theme.accentSoft)};
              --panel: rgba(12, 18, 30, 0.88);
            }
            body {
              background: linear-gradient(165deg, var(--bg-top) 0%, var(--bg-bottom) 72%);
            }
            body::before {
              content: '';
              position: fixed;
              inset: 0;
              background: radial-gradient(circle at 78% 28%, color-mix(in srgb, var(--accent) 28%, transparent), transparent 58%);
              pointer-events: none;
            }
            .watermark {
              position: fixed;
              right: 2rem;
              bottom: 1rem;
              font-size: 9rem;
              opacity: 0.12;
              pointer-events: none;
              user-select: none;
            }
            header, .status, .event { background: var(--panel); }
            h2, .event .time { color: var(--accent-soft); }
            .event.live { border-left-color: var(--accent); }
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private const val BASE_STYLES = """
        :root { color-scheme: dark; --text: #e8eef5; --muted: #96a8c0; --live: #3dd68c; }
        * { box-sizing: border-box; }
        body { margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; color: var(--text); line-height: 1.45; min-height: 100vh; }
        main { max-width: 900px; margin: 0 auto; padding: 1.25rem 1rem 2rem; position: relative; z-index: 1; }
        header { margin-bottom: 1rem; border-radius: 14px; padding: 1rem 1.1rem; border-left: 4px solid var(--accent); }
        .eyebrow { margin: 0 0 0.35rem; font-size: 0.85rem; color: var(--muted); }
        h1 { margin: 0 0 0.35rem; font-size: 1.65rem; font-weight: 700; }
        .subtitle { margin: 0; color: var(--muted); font-size: 0.95rem; }
        h2 { margin: 1rem 0 0.55rem; font-size: 1rem; text-transform: uppercase; letter-spacing: 0.05em; }
        .status { border-radius: 12px; padding: 0.85rem 1rem; margin-bottom: 0.5rem; }
        .status p { margin: 0; }
        .status .empty { color: var(--muted); }
        .status .ok { color: var(--text); }
        .events { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.5rem; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }
        .event { border-radius: 12px; padding: 0.8rem 0.95rem; display: grid; gap: 0.2rem; border-left: 4px solid transparent; }
        .event.live { border-left-color: var(--live); }
        .event .time { font-size: 0.9rem; font-weight: 600; }
        .event .title { font-size: 1rem; }
        .event .date { font-size: 0.82rem; color: var(--muted); }
        footer { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid rgba(255,255,255,0.08); color: var(--muted); font-size: 0.85rem; }
        footer p { margin: 0.35rem 0; }
    """
}
