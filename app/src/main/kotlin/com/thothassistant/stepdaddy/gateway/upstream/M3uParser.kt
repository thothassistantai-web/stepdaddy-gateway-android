package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Minimal M3U parser for supplement playlists (TVApp2-style EXTINF + URL lines).
 */
object M3uParser {
    data class Entry(
        val name: String,
        val tvgId: String? = null,
        val logo: String? = null,
        val groupTitle: String? = null,
        val streamUrl: String,
        /** Source playlist filename when parsed from iptv-org GitHub streams. */
        val sourcePlaylist: String? = null,
    )

    fun parse(text: String): List<Entry> {
        val lines = text.lines()
        val out = mutableListOf<Entry>()
        var pending: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", ignoreCase = true)) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pending = line
                continue
            }
            if (line.startsWith("#")) continue
            val extinf = pending ?: continue
            pending = null
            val name = extractDisplayName(extinf)
            if (name.isEmpty()) continue
            out += Entry(
                name = name,
                tvgId = attrValue(extinf, "tvg-id"),
                logo = attrValue(extinf, "tvg-logo"),
                groupTitle = attrValue(extinf, "group-title"),
                streamUrl = line,
            )
        }
        return out
    }

    private fun extractDisplayName(extinf: String): String {
        val comma = extinf.lastIndexOf(',')
        if (comma < 0 || comma >= extinf.lastIndex) return ""
        return extinf.substring(comma + 1).trim()
    }

    private fun attrValue(extinf: String, key: String): String? {
        val pattern = Regex("""$key="((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
        val match = pattern.find(extinf) ?: return null
        return unescape(match.groupValues[1]).trim().ifEmpty { null }
    }

    private fun unescape(value: String): String =
        value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}
