package com.thothassistant.stepdaddy.gateway.sidecar

/**
 * Slim TVApp2 replacement: keep only direct MoveOnJoy HLS lines from the shared
 * formatted playlist (drops TheTvApp/TVPass token-proxy entries the gateway blocks anyway).
 */
object MoveOnJoyPlaylistBuilder {
    private val moveOnJoyUrlRe = Regex("""https?://fl\d+\.moveonjoy\.com/[^\s|]+""", RegexOption.IGNORE_CASE)

    fun fromFormattedPlaylist(raw: String, baseUrl: String = SidecarConfig.LOOPBACK_BASE): String {
        val base = baseUrl.trimEnd('/')
        val header = "#EXTM3U url-tvg=\"$base/xmltv.xml\" x-tvg-url=\"$base/xmltv.xml\"\n"
        if (raw.isBlank()) return header

        val out = StringBuilder(header.length + raw.length / 4)
        out.append(header)

        val lines = raw.lineSequence().map { it.trimEnd() }.toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("#EXTINF:", ignoreCase = true)) {
                index++
                continue
            }
            val urlLine = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (!isMoveOnJoyStream(urlLine)) {
                index += 2
                continue
            }
            out.append(line).append('\n')
            out.append(urlLine).append('\n')
            index += 2
        }
        return out.toString()
    }

    fun isMoveOnJoyStream(url: String): Boolean {
        val trimmed = url.substringBefore('|').trim().lowercase()
        return trimmed.contains("moveonjoy.com") && trimmed.endsWith(".m3u8")
    }

    fun countMoveOnJoyEntries(raw: String): Int =
        moveOnJoyUrlRe.findAll(raw).count()
}
