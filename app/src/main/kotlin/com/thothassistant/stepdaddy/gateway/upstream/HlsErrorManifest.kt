package com.thothassistant.stepdaddy.gateway.upstream

object HlsErrorManifest {
    /** Minimal HLS body so IPTV players fail fast instead of spinning on JSON errors. */
    fun build(message: String): String {
        val safe = message.replace("\n", " ").replace("\r", " ").take(120)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:1")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("# StepDaddy: $safe")
            appendLine("#EXTINF:1.0,unavailable")
            appendLine("unavailable.ts")
        }
    }
}
