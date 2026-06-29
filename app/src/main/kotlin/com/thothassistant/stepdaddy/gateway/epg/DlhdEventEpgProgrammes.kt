package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventSourceMeta
import com.thothassistant.stepdaddy.gateway.upstream.EventScheduleTimesResolver
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventLifecycle
import java.time.Instant

/** Emits XMLTV programme rows for `dlhd-event:` channels from [EventScheduleTimes]. */
object DlhdEventEpgProgrammes {
    fun programmeForChannel(
        channel: SupplementChannel,
        schedule: EventScheduleTimes,
        now: Instant = Instant.now(),
    ): SpecialEventsEpgGenerator.EventProgramme? {
        if (!channel.id.startsWith("dlhd-event:")) return null
        val tvgId = channel.tvgId?.trim().orEmpty()
        if (tvgId.isEmpty()) return null
        val start = schedule.startInstant()
        val stop = schedule.stopInstant()
        if (!SpecialEventLifecycle.isActive(start, stop, now)) return null
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl)
        val title = meta?.displayTitle()?.ifEmpty { null }
            ?: channel.name.substringAfter(": ", channel.name).trim().ifEmpty { channel.name }
        return SpecialEventsEpgGenerator.EventProgramme(
            channelId = tvgId,
            displayName = channel.name,
            title = title,
            start = start,
            stop = stop,
            regionCode = channel.regionCode,
            languageCode = channel.languageCode,
        )
    }

    fun programmesForChannels(
        channels: List<SupplementChannel>,
        now: Instant = Instant.now(),
    ): List<SpecialEventsEpgGenerator.EventProgramme> =
        channels.mapNotNull { channel ->
            val schedule = EventScheduleTimesResolver.fromChannel(channel) ?: return@mapNotNull null
            programmeForChannel(channel, schedule, now)
        }
}
