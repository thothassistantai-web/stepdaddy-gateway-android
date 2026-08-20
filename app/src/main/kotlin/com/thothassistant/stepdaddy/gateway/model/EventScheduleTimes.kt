package com.thothassistant.stepdaddy.gateway.model

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parsed start/end window for a special-event token (EPG + lifecycle). */
@Serializable
data class EventScheduleTimes(
    val eventToken: String,
    val startIso: String,
    val stopIso: String,
    val source: EventScheduleSource,
    val live: Boolean = false,
) {
    val startMs: Long
        get() = Instant.parse(startIso).toEpochMilli()

    val stopMs: Long
        get() = Instant.parse(stopIso).toEpochMilli()

    fun startInstant(): Instant = Instant.parse(startIso)

    fun stopInstant(): Instant = Instant.parse(stopIso)

    fun window(): Pair<Instant, Instant> =
        startInstant() to stopInstant()

    companion object {
        fun of(
            eventToken: String,
            start: Instant,
            stop: Instant,
            source: EventScheduleSource,
            live: Boolean = false,
        ): EventScheduleTimes =
            EventScheduleTimes(
                eventToken = eventToken,
                startIso = start.truncatedTo(ChronoUnit.SECONDS).toString(),
                stopIso = stop.truncatedTo(ChronoUnit.SECONDS).toString(),
                source = source,
                live = live,
            )

        fun ofEpochMs(
            startMs: Long,
            stopMs: Long,
            source: EventScheduleSource = EventScheduleSource.DLHD_TV,
            eventToken: String = "",
            live: Boolean = false,
        ): EventScheduleTimes = of(
            eventToken = eventToken,
            start = Instant.ofEpochMilli(startMs),
            stop = Instant.ofEpochMilli(stopMs),
            source = source,
            live = live,
        )
    }
}

@Serializable
enum class EventScheduleSource {
    @SerialName("dlhd_tv")
    DLHD_TV,

    @SerialName("dlhd_tv2")
    DLHD_TV2,
}
