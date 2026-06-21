package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object TheTvAppSportsEpgGenerator {
    private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private const val EVENT_HOURS = 4L

    data class EventProgramme(
        val channelId: String,
        val displayName: String,
        val title: String,
        val start: Instant,
        val stop: Instant,
    )

    fun programmesForChannels(channels: List<SupplementChannel>): List<EventProgramme> {
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val stop = now.plus(EVENT_HOURS, ChronoUnit.HOURS)
        return channels.mapNotNull { channel ->
            val tvgId = channel.tvgId?.trim().orEmpty()
            if (tvgId.isEmpty() || !channel.id.startsWith("sport:")) return@mapNotNull null
            EventProgramme(
                channelId = tvgId,
                displayName = channel.name,
                title = channel.name,
                start = now,
                stop = stop,
            )
        }
    }

    fun writeXml(events: List<EventProgramme>, output: File) {
        output.parentFile?.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.write("\n<tv generator-info-name=\"StepDaddy Sports\">")
            val writtenChannels = linkedSetOf<String>()
            for (event in events) {
                if (writtenChannels.add(event.channelId)) {
                    writer.write("\n<channel id=\"${escape(event.channelId)}\">")
                    writer.write("<display-name>${escape(event.displayName)}</display-name>")
                    writer.write("</channel>")
                }
                writer.write("\n<programme start=\"${format(event.start)}\" stop=\"${format(event.stop)}\" channel=\"${escape(event.channelId)}\">")
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
