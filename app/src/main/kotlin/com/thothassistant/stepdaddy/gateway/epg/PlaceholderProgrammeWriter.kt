package com.thothassistant.stepdaddy.gateway.epg

import java.io.BufferedWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object PlaceholderProgrammeWriter {
    const val DEFAULT_TITLE = "Live programming"
    private const val BLOCK_HOURS = 2L
    private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    fun appendPlaceholders(
        writer: BufferedWriter,
        channelIds: Set<String>,
        channelNames: Map<String, String>,
        windowStart: Instant,
        windowEnd: Instant,
        writtenChannelIds: MutableSet<String>,
        idsWithProgrammes: MutableSet<String>,
    ): Int {
        if (channelIds.isEmpty()) return 0
        var count = 0
        var blockStart = windowStart.truncatedTo(ChronoUnit.MINUTES)
        while (blockStart.isBefore(windowEnd)) {
            val blockStop = blockStart.plus(BLOCK_HOURS, ChronoUnit.HOURS)
            if (blockStop.isAfter(windowEnd)) break
            for (channelId in channelIds) {
                if (channelId in idsWithProgrammes) continue
                if (channelId !in writtenChannelIds) {
                    val name = channelNames[channelId]?.trim().orEmpty().ifEmpty { channelId }
                    writer.write("\n<channel id=\"${escape(channelId)}\">")
                    writer.write("<display-name>${escape(name)}</display-name>")
                    writer.write("</channel>")
                    writtenChannelIds += channelId
                }
                writer.write(
                    "\n<programme start=\"${format(blockStart)}\" stop=\"${format(blockStop)}\" channel=\"${escape(channelId)}\">",
                )
                writer.write("<title>${escape(DEFAULT_TITLE)}</title>")
                writer.write("</programme>")
                count++
                idsWithProgrammes += channelId
            }
            blockStart = blockStop
        }
        return count
    }

    private fun format(instant: Instant): String = XMLTV_TIME.format(instant)

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
