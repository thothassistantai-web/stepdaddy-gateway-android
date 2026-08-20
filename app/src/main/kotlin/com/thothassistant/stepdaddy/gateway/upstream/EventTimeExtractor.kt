package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Extracts scheduled start/end windows from DaddyLive `tv.json` / `tv2.json` rows.
 */
object EventTimeExtractor {
    const val LIVE_DURATION_HOURS = 4L
    const val SCHEDULED_DURATION_HOURS = 3L

    fun dlhdStreamToken(stream: DaddyLiveEventResolver.ParsedStream): String =
        when (stream.source) {
            DaddyLiveEventResolver.StreamSource.TV -> "tv|${stream.channelId}"
            DaddyLiveEventResolver.StreamSource.TV2 -> "tv2|${stream.channelId}"
        }

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
}
