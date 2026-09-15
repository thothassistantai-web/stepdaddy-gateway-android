package com.thothassistant.stepdaddy.gateway.upstream

import java.util.Base64
import java.util.regex.Pattern

/**
 * Decodes assetrage-style `window._econfig` blobs used by modern DaddyLive hub players.
 * Same algorithm as the Linux gateway `ddl_link_discovery.decode_econfig_blob`.
 *
 * Avoids `org.json` so JVM unit tests work (Android stubs throw on JSONObject).
 */
object EconfigDecoder {
    private val ECONFIG =
        Pattern.compile("""window\._econfig\s*=\s*['"]([^'"]+)['"]""")
    private val STREAM_URL_NOP2P =
        Pattern.compile(""""stream_url_nop2p"\s*:\s*"([^"]+)"""")
    private val STREAM_URL =
        Pattern.compile(""""stream_url"\s*:\s*"([^"]+)"""")

    data class StreamConfig(
        val streamUrl: String,
        val rawJson: String,
    )

    fun extractStreamUrl(html: String): String? = decode(html)?.streamUrl

    fun decode(html: String): StreamConfig? {
        val matcher = ECONFIG.matcher(html)
        if (!matcher.find()) return null
        val blob = matcher.group(1) ?: return null
        val json = decodeBlob(blob) ?: return null
        val url =
            firstGroup(STREAM_URL_NOP2P, json)
                ?.ifBlank { null }
                ?: firstGroup(STREAM_URL, json)
                    ?.trim()
                    .orEmpty()
        val normalized = normalizeStreamUrl(url)
        if (!normalized.startsWith("http") || !normalized.contains(".m3u8", ignoreCase = true)) {
            return null
        }
        return StreamConfig(streamUrl = normalized, rawJson = json)
    }

    /** JSON string values often escape slashes as `\/` — strip those so OkHttp can fetch. */
    fun normalizeStreamUrl(url: String): String =
        url.trim()
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    /** Returns UTF-8 JSON text, or null if the blob is invalid. */
    fun decodeBlob(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val s = String(b64(raw), Charsets.ISO_8859_1)
            if (s.length < 16 || s.length % 4 != 0) return null
            val n = 4
            val chunk = s.length / n
            val parts = (0 until n).map { i -> s.substring(i * chunk, (i + 1) * chunk) }
            val order = intArrayOf(2, 0, 3, 1)
            val out = arrayOfNulls<String>(4)
            for (i in parts.indices) {
                val part = parts[i]
                val trimmed = part.substring(0, 3) + part.substring(4)
                out[order[i]] = String(b64(trimmed), Charsets.ISO_8859_1)
            }
            val joined = out.joinToString("") { it.orEmpty() }
            String(b64(joined), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun firstGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return null
        return matcher.group(1)
    }

    private fun b64(data: String): ByteArray {
        val pad = "=".repeat((4 - data.length % 4) % 4)
        return Base64.getDecoder().decode(data + pad)
    }
}
