package com.thothassistant.stepdaddy.gateway.upstream

import java.net.URL

object M3u8Rewriter {
    fun rewrite(
        m3u8Text: String,
        m3u8Url: String,
        refererHost: String = "",
        useProxy: Boolean,
        apiUrl: String = "",
        preferLighterVariant: Boolean = true,
        /** When set with [useProxy], segment/key URLs use `/vod-content/` with this embed referer. */
        segmentReferer: String? = null,
    ): String {
        var text = m3u8Text
        if (preferLighterVariant) {
            text = filterMasterVariant(text)
        }
        val linesOut = mutableListOf<String>()
        var nonCommentCount = 0
        for (rawLine in text.lines()) {
            var line = rawLine.trim()
            if (line.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                val uriMatch = URI_PATTERN.find(line)
                if (uriMatch != null) {
                    val originalUrl = uriMatch.groupValues[1]
                    val absoluteKeyUrl = resolveUrl(m3u8Url, originalUrl)
                    val keyReferer = segmentReferer?.takeIf { it.isNotBlank() } ?: refererHost
                    line = if (useProxy && apiUrl.isNotBlank()) {
                        line.replace(
                            originalUrl,
                            "${apiUrl.trimEnd('/')}/key/${ContentCrypto.encrypt(absoluteKeyUrl)}/${ContentCrypto.encrypt(keyReferer)}",
                        )
                    } else {
                        line.replace(originalUrl, absoluteKeyUrl)
                    }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                nonCommentCount++
                val absoluteMediaUrl = resolveUrl(m3u8Url, line)
                line = if (useProxy && apiUrl.isNotBlank()) {
                    proxyContentUrl(apiUrl, absoluteMediaUrl, segmentReferer)
                } else {
                    absoluteMediaUrl
                }
            }
            linesOut += line
        }
        val hasExtM3u = linesOut.any { it.startsWith("#EXTM3U") }
        if (!hasExtM3u && nonCommentCount == 1) {
            val mediaLine = linesOut.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
            return "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\n$mediaLine\n"
        }
        return linesOut.joinToString("\n").trimEnd() + "\n"
    }

    private fun filterMasterVariant(m3u8Text: String, maxHeight: Int = 720): String {
        val lines = m3u8Text.lines().map { it.trimEnd('\r') }
        val variants = mutableListOf<Variant>()
        var index = 0
        while (index < lines.size) {
            val stripped = lines[index].trim()
            if (!stripped.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                index++
                continue
            }
            val block = mutableListOf(lines[index])
            index++
            while (index < lines.size) {
                val peek = lines[index].trim()
                if (peek.isEmpty()) {
                    index++
                    continue
                }
                if (peek.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    break
                }
                block += lines[index]
                if (!peek.startsWith("#")) {
                    index++
                    break
                }
                index++
            }
            var height = 0
            var bandwidth = 0
            block.forEach { entry ->
                RESOLUTION_PATTERN.find(entry)?.let { match ->
                    height = match.groupValues[2].toIntOrNull() ?: height
                }
                BANDWIDTH_PATTERN.find(entry)?.let { match ->
                    bandwidth = match.groupValues[1].toIntOrNull() ?: bandwidth
                }
            }
            variants += Variant(height, bandwidth, block)
        }
        if (variants.size <= 1) {
            return m3u8Text
        }
        val within = variants.filter { it.height == 0 || it.height <= maxHeight }
        val pool = within.ifEmpty { variants }
        val winner = pool.minWith(compareBy<Variant> { if (it.height == 0) 99_999 else it.height }
            .thenBy { if (it.bandwidth == 0) 999_999_999 else it.bandwidth })
        val header = lines.filter { it.trim().startsWith("#EXTM3U") }.ifEmpty { listOf("#EXTM3U") }
        return (header + winner.block).joinToString("\n").trimEnd() + "\n"
    }

    fun proxyContentUrl(apiUrl: String, absoluteMediaUrl: String, segmentReferer: String?): String {
        val base = apiUrl.trimEnd('/')
        val encrypted = ContentCrypto.encrypt(absoluteMediaUrl)
        val referer = segmentReferer?.trim()?.takeIf { it.isNotEmpty() }
        return if (referer != null) {
            "$base/vod-content/$encrypted/${ContentCrypto.encrypt(referer)}"
        } else {
            "$base/content/$encrypted"
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        return URL(URL(base), relative).toString()
    }

    private val URI_PATTERN = Regex("""URI="(.*?)"""")
    private val RESOLUTION_PATTERN = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val BANDWIDTH_PATTERN = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)

    private data class Variant(
        val height: Int,
        val bandwidth: Int,
        val block: List<String>,
    )
}
