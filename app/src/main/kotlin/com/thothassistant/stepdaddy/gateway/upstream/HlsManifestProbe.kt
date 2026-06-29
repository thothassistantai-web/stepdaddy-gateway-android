package com.thothassistant.stepdaddy.gateway.upstream

import java.net.URL

/** Helpers for lightweight HLS manifest/segment reachability checks. */
object HlsManifestProbe {
    fun isMasterPlaylist(m3u8Text: String): Boolean =
        m3u8Text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    fun firstMediaUrl(m3u8Text: String, manifestUrl: String): String? {
        for (rawLine in m3u8Text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return resolveUrl(manifestUrl, line)
        }
        return null
    }

    fun firstSegmentUrl(m3u8Text: String, manifestUrl: String): String? {
        var expectSegment = false
        for (rawLine in m3u8Text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                expectSegment = true
                continue
            }
            if (!expectSegment || line.isEmpty() || line.startsWith("#")) continue
            return resolveUrl(manifestUrl, line)
        }
        return null
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        return URL(URL(base), relative).toString()
    }
}
