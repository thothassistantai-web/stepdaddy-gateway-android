package com.thothassistant.stepdaddy.gateway.upstream

object HlsErrorManifest {
    /**
     * Minimal HLS master so IPTV players fail fast with a readable message instead of
     * spinning on JSON errors or throwing [ParserException] on fake EXTINF segments.
     */
    fun build(message: String): String {
        val safe = message.replace("\n", " ").replace("\r", " ").take(120)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("# StepDaddy: $safe")
        }
    }
}
