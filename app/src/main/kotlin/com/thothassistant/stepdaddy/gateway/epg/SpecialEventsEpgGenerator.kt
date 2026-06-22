package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventSourceMeta
import com.thothassistant.stepdaddy.gateway.upstream.DlhdScheduleTime
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventLifecycle
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
                    if (rows.isEmpty()) {
                        out += placeholderGuideProgramme(tvgId, channel.name, "No upcoming events", now)
                    } else {
                        rows.mapNotNullTo(out) { row ->
                            guideProgramme(tvgId, channel.name, row, now)
                        }
                    }
                }
                channel.id.startsWith("dlhd-event:") -> {
                    programmeForStream(channel, now)?.let { out += it }
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

    private fun programmeForStream(channel: SupplementChannel, now: Instant): EventProgramme? {
        val tvgId = channel.tvgId?.trim().orEmpty()
        if (tvgId.isEmpty()) return null
        val meta = DlhdEventSourceMeta.parse(channel.eventSourceUrl)
        val title = meta?.displayTitle()?.ifEmpty { null }
            ?: channel.name.substringAfter(": ", channel.name).trim().ifEmpty { channel.name }
        val dateKey = meta?.dateKey.orEmpty()
        val timeLabel = meta?.timeLabel.orEmpty()
        val (start, stop) = DlhdScheduleTime.parseWindow(dateKey, timeLabel)
        if (!SpecialEventLifecycle.isActive(start, stop, now)) return null
        return EventProgramme(
            channelId = tvgId,
            displayName = channel.name,
            title = title,
            start = start,
            stop = stop,
        )
    }

    private fun programmeForLiveStream(channel: SupplementChannel, now: Instant): EventProgramme? {
        val tvgId = channel.tvgId?.trim().orEmpty()
        if (tvgId.isEmpty()) return null
        val start = now.truncatedTo(ChronoUnit.MINUTES)
        val title = channel.name.trim().ifEmpty { "Live event" }
        return EventProgramme(
            channelId = tvgId,
            displayName = channel.name,
            title = title,
            start = start,
            stop = start.plus(LIVE_EVENT_HOURS, ChronoUnit.HOURS),
        )
    }

    fun writeXml(events: List<EventProgramme>, output: File) {
        output.parentFile?.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.write("\n<tv generator-info-name=\"StepDaddy Special Events\">")
            val writtenChannels = linkedSetOf<String>()
            for (event in events) {
                if (writtenChannels.add(event.channelId)) {
                    writer.write("\n<channel id=\"${escape(event.channelId)}\">")
                    writer.write("<display-name>${escape(event.displayName)}</display-name>")
                    writer.write("</channel>")
                }
                writer.write(
                    "\n<programme start=\"${format(event.start)}\" stop=\"${format(event.stop)}\" " +
                        "channel=\"${escape(event.channelId)}\">",
                )
                writer.write("<title>${escape(event.title)}</title>")
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
