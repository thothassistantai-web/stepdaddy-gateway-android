package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.EventScheduleTimesResolver
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventLifecycle
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventRegionIdentifier
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsMerger
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Unified XMLTV for Special Events (DaddyLive schedule + TheTvApp live). */
object SpecialEventsEpgGenerator {
    private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private const val LIVE_EVENT_HOURS = 4L

    data class EventProgramme(
        val channelId: String,
        val displayName: String,
        val title: String,
        val start: Instant,
        val stop: Instant,
        val regionCode: String? = null,
        val languageCode: String? = null,
    )

    fun programmesForBundle(
        channels: List<SupplementChannel>,
        guideProgrammes: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
        now: Instant = Instant.now(),
    ): List<EventProgramme> {
        val out = mutableListOf<EventProgramme>()
        channels.forEach { channel ->
            when {
                channel.id.startsWith("dlhd-guide:") -> {
                    val tvgId = channel.tvgId?.trim().orEmpty()
                    if (tvgId.isEmpty()) return@forEach
                    val rows = guideProgrammes[channel.id].orEmpty()
                        .filter { row -> SpecialEventLifecycle.isActive(row.startMs, row.stopMs, now.toEpochMilli()) }
                        .sortedBy { it.startMs }
                    when {
                        rows.isEmpty() -> {
                            out += placeholderGuideProgramme(tvgId, channel.name, "No upcoming events", now)
                        }
                        rows.size == 1 -> {
                            guideProgramme(tvgId, channel.name, rows.single(), now)?.let { out += it }
                        }
                        else -> {
                            rows.mapNotNullTo(out) { row ->
                                guideProgramme(tvgId, channel.name, row, now)
                            }
                        }
                    }
                }
                channel.id.startsWith("dlhd-event:") -> {
                    val schedule = EventScheduleTimesResolver.fromChannel(channel) ?: return@forEach
                    DlhdEventEpgProgrammes.programmeForChannel(channel, schedule, now)?.let { out += it }
                }
                channel.id.startsWith("sport:") -> {
                    programmeForLiveStream(channel, now)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun guideProgramme(
        tvgId: String,
        displayName: String,
        row: SpecialEventsMerger.GuideEventRow,
        now: Instant,
    ): EventProgramme? {
        val start = Instant.ofEpochMilli(row.startMs)
        val stop = Instant.ofEpochMilli(row.stopMs)
        if (!SpecialEventLifecycle.isActive(start, stop, now)) return null
        return EventProgramme(
            channelId = tvgId,
            displayName = displayName,
            title = row.title.substringAfter(": ", row.title).trim().ifEmpty { row.title },
            start = start,
            stop = stop,
            regionCode = row.regionCode,
        )
    }

    private fun placeholderGuideProgramme(
        tvgId: String,
        displayName: String,
        title: String,
        now: Instant,
    ): EventProgramme {
        val start = now.truncatedTo(ChronoUnit.MINUTES)
        return EventProgramme(
            channelId = tvgId,
            displayName = displayName,
            title = title,
            start = start,
            stop = start.plus(LIVE_EVENT_HOURS, ChronoUnit.HOURS),
        )
    }

    private fun programmeForLiveStream(channel: SupplementChannel, now: Instant): EventProgramme? {
        val tvgId = channel.tvgId?.trim().orEmpty()
        if (tvgId.isEmpty()) return null
        val startMs = channel.eventStartMs
        val stopMs = channel.eventStopMs
        val (start, stop) = if (startMs != null && stopMs != null && stopMs > startMs) {
            Instant.ofEpochMilli(startMs) to Instant.ofEpochMilli(stopMs)
        } else {
            val anchor = now.truncatedTo(ChronoUnit.MINUTES)
            anchor to anchor.plus(LIVE_EVENT_HOURS, ChronoUnit.HOURS)
        }
        if (!SpecialEventLifecycle.isActive(start, stop, now)) return null
        val title = channel.name.trim().ifEmpty { "Live event" }
        return EventProgramme(
            channelId = tvgId,
            displayName = channel.name,
            title = title,
            start = start,
            stop = stop,
            regionCode = channel.regionCode,
            languageCode = channel.languageCode,
        )
    }

    fun writeXml(events: List<EventProgramme>, output: File) {
        output.parentFile?.mkdirs()
        val channelMeta = linkedMapOf<String, Pair<String?, String?>>()
        events.forEach { event ->
            val existing = channelMeta[event.channelId]
            channelMeta[event.channelId] = Pair(
                event.regionCode?.takeIf { it.isNotBlank() } ?: existing?.first,
                event.languageCode?.takeIf { it.isNotBlank() } ?: existing?.second,
            )
        }
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.write("\n<tv generator-info-name=\"StepDaddy Special Events\">")
            val writtenChannels = linkedSetOf<String>()
            for (event in events) {
                if (writtenChannels.add(event.channelId)) {
                    writer.write("\n<channel id=\"${escape(event.channelId)}\">")
                    writer.write("<display-name>${escape(event.displayName)}</display-name>")
                    channelMeta[event.channelId]?.first?.let { region ->
                        writer.write("<country>${escape(SpecialEventRegionIdentifier.normalizeCode(region))}</country>")
                    }
                    channelMeta[event.channelId]?.second?.let { lang ->
                        writer.write("<language>${escape(lang)}</language>")
                    }
                    writer.write("</channel>")
                }
                writer.write(
                    "\n<programme start=\"${format(event.start)}\" stop=\"${format(event.stop)}\" " +
                        "channel=\"${escape(event.channelId)}\">",
                )
                writer.write("<title>${escape(event.title)}</title>")
                event.regionCode?.takeIf { it.isNotBlank() }?.let { region ->
                    writer.write("<country>${escape(SpecialEventRegionIdentifier.normalizeCode(region))}</country>")
                }
                event.languageCode?.takeIf { it.isNotBlank() }?.let { lang ->
                    writer.write("<language>${escape(lang)}</language>")
                }
                writer.write("</programme>")
            }
            writer.write("\n</tv>\n")
        }
    }

    private fun format(instant: Instant): String = XMLTV_TIME.format(instant)

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
