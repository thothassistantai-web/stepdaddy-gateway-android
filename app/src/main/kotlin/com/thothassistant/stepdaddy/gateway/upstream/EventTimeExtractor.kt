package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Extracts scheduled start/end windows from DaddyLive `tv.json` / `tv2.json` rows and
 * TheTvApp event pages (relative `time-badge` labels).
 */
object EventTimeExtractor {
    const val LIVE_DURATION_HOURS = 4L
    const val SCHEDULED_DURATION_HOURS = 3L

    private val theTvAppBadgePattern = Regex(
        """<span\s+class="time-badge">\s*([^<]+?)\s*</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val minutesFromNowPattern = Regex("""(\d+)\s+minutes?\s+from\s+now""", RegexOption.IGNORE_CASE)
    private val hoursFromNowPattern = Regex("""(\d+)\s+hours?\s+from\s+now""", RegexOption.IGNORE_CASE)
    private val daysFromNowPattern = Regex("""(\d+)\s+days?\s+from\s+now""", RegexOption.IGNORE_CASE)
    private val minutesAgoPattern = Regex("""(\d+)\s+minutes?\s+ago""", RegexOption.IGNORE_CASE)
    private val hoursAgoPattern = Regex("""(\d+)\s+hours?\s+ago""", RegexOption.IGNORE_CASE)
    private val daysAgoPattern = Regex("""(\d+)\s+days?\s+ago""", RegexOption.IGNORE_CASE)
    private val liveNowPattern = Regex(
        """\b(?:in\s+progress|halftime|overtime|1st\s+half|2nd\s+half|live(?:\s+now)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun dlhdStreamToken(stream: DaddyLiveEventResolver.ParsedStream): String =
        when (stream.source) {
            DaddyLiveEventResolver.StreamSource.TV -> "tv|${stream.channelId}"
            DaddyLiveEventResolver.StreamSource.TV2 -> "tv2|${stream.channelId}"
        }

    fun theTvAppToken(eventUrl: String): String =
        "thetvapp|${SpecialEventsMerger.shortHash(eventUrl.trim())}"

    fun fromDlhdSchedule(
        dateKey: String,
        timeLabel: String,
        eventToken: String,
        source: EventScheduleSource,
        now: Instant = Instant.now(),
    ): EventScheduleTimes {
        val live = timeLabel.trim().equals("live", ignoreCase = true)
        val (start, stop) = DlhdScheduleTime.parseWindow(
            dateKey = dateKey,
            timeLabel = timeLabel,
            liveHours = LIVE_DURATION_HOURS,
        )
        val adjustedStart = if (live) now.truncatedTo(ChronoUnit.MINUTES) else start
        val adjustedStop = if (live) {
            adjustedStart.plus(LIVE_DURATION_HOURS, ChronoUnit.HOURS)
        } else {
            stop
        }
        return EventScheduleTimes.of(
            eventToken = eventToken,
            start = adjustedStart,
            stop = adjustedStop,
            source = source,
            live = live,
        )
    }

    fun fromDlhdParsedEvent(
        event: DaddyLiveEventResolver.ParsedEvent,
        stream: DaddyLiveEventResolver.ParsedStream,
        now: Instant = Instant.now(),
    ): EventScheduleTimes {
        val source = when (stream.source) {
            DaddyLiveEventResolver.StreamSource.TV2 -> EventScheduleSource.DLHD_TV2
            DaddyLiveEventResolver.StreamSource.TV -> EventScheduleSource.DLHD_TV
        }
        return fromDlhdSchedule(
            dateKey = event.dateKey,
            timeLabel = event.timeLabel,
            eventToken = dlhdStreamToken(stream),
            source = source,
            now = now,
        )
    }

    fun fromDlhdFeeds(
        tvJson: String?,
        tv2Json: String?,
        now: Instant = Instant.now(),
    ): Map<String, EventScheduleTimes> {
        val resolver = DaddyLiveEventResolver()
        val (events, _) = resolver.parseFeeds(tvJson, tv2Json)
        return buildMap {
            events.forEach { event ->
                event.streams.forEach { stream ->
                    val times = fromDlhdParsedEvent(event, stream, now)
                    put(times.eventToken, times)
                }
            }
        }
    }

    fun fromTheTvAppEventHtml(
        html: String,
        eventUrl: String,
        now: Instant = Instant.now(),
    ): EventScheduleTimes? {
        val badge = extractTheTvAppBadge(html) ?: return null
        val window = parseTheTvAppBadge(badge, now) ?: return null
        val live = isTheTvAppLiveBadge(badge)
        return EventScheduleTimes.of(
            eventToken = theTvAppToken(eventUrl),
            start = window.first,
            stop = window.second,
            source = EventScheduleSource.THE_TV_APP,
            live = live,
        )
    }

    fun extractTheTvAppBadge(html: String): String? =
        theTvAppBadgePattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifEmpty { null }

    fun parseTheTvAppBadge(badge: String, now: Instant = Instant.now()): Pair<Instant, Instant>? {
        val label = badge.trim()
        if (label.isEmpty()) return null
        val anchor = now.truncatedTo(ChronoUnit.MINUTES)

        minutesFromNowPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { minutes ->
            val start = anchor.plus(minutes, ChronoUnit.MINUTES)
            return start to start.plus(SCHEDULED_DURATION_HOURS, ChronoUnit.HOURS)
        }
        hoursFromNowPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { hours ->
            val start = anchor.plus(hours, ChronoUnit.HOURS)
            return start to start.plus(SCHEDULED_DURATION_HOURS, ChronoUnit.HOURS)
        }
        daysFromNowPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { days ->
            val start = anchor.plus(days, ChronoUnit.DAYS)
            return start to start.plus(SCHEDULED_DURATION_HOURS, ChronoUnit.HOURS)
        }
        minutesAgoPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { minutes ->
            val start = anchor.minus(minutes, ChronoUnit.MINUTES)
            return start to start.plus(LIVE_DURATION_HOURS, ChronoUnit.HOURS)
        }
        hoursAgoPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { hours ->
            val start = anchor.minus(hours, ChronoUnit.HOURS)
            return start to start.plus(LIVE_DURATION_HOURS, ChronoUnit.HOURS)
        }
        daysAgoPattern.find(label)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { days ->
            val start = anchor.minus(days, ChronoUnit.DAYS)
            return start to start.plus(LIVE_DURATION_HOURS, ChronoUnit.HOURS)
        }
        if (liveNowPattern.containsMatchIn(label)) {
            return anchor to anchor.plus(LIVE_DURATION_HOURS, ChronoUnit.HOURS)
        }
        return null
    }

    private fun isTheTvAppLiveBadge(badge: String): Boolean {
        val label = badge.trim()
        if (liveNowPattern.containsMatchIn(label)) return true
        return minutesAgoPattern.containsMatchIn(label) ||
            hoursAgoPattern.containsMatchIn(label) ||
            daysAgoPattern.containsMatchIn(label)
    }
}
