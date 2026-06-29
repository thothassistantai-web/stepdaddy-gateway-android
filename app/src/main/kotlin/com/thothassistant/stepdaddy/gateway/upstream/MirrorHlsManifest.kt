package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Multi-variant HLS master for consolidated dlhd-event mirrors (pattern B).
 * Each variant points at a gateway mirror sub-playlist so ExoPlayer can fail over.
 */
object MirrorHlsManifest {
    fun build(
        baseUrl: String,
        eventToken: String,
        mirrorCount: Int,
        labels: List<String> = emptyList(),
    ): String {
        val base = baseUrl.trimEnd('/')
        val count = mirrorCount.coerceAtLeast(1)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            repeat(count) { index ->
                val label = labels.getOrNull(index)?.trim().orEmpty()
                val nameAttr = if (label.isNotEmpty()) {
                    ",NAME=\"${escapeAttr(label)}\""
                } else {
                    ",NAME=\"Mirror ${index + 1}\""
                }
                val bandwidth = (1_500_000 - index * 50_000).coerceAtLeast(400_000)
                appendLine(
                    "#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth$nameAttr",
                )
                appendLine("$base/dlhd-event-mirror/$eventToken/$index.m3u8")
            }
        }
    }

    private fun escapeAttr(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .trim()
}
