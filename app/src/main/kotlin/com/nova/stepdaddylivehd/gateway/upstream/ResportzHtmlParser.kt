package com.nova.stepdaddylivehd.gateway.upstream

import java.net.URL
import java.util.Base64
import java.util.regex.Pattern

/**
 * Pure HTML parsing for resportz relay pages. Kept Android-free for unit tests.
 */
object ResportzHtmlParser {
    data class PatternMatch(
        val pattern: String,
        val value: String,
    )

    private val THATFRAME_SRC =
        Pattern.compile(
            """<iframe[^>]*\bid=["']thatframe["'][^>]*\bsrc=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        )
    private val SRC_THEN_THATFRAME =
        Pattern.compile(
            """<iframe[^>]*\bsrc=["']([^"']+)["'][^>]*\bid=["']thatframe["']""",
            Pattern.CASE_INSENSITIVE,
        )
    private val IFRAME_SRC_DOUBLE =
        Pattern.compile("""<iframe[^>]*\bsrc="([^"]+)"""", Pattern.CASE_INSENSITIVE)
    private val IFRAME_SRC_SINGLE =
        Pattern.compile("""<iframe[^>]*\bsrc='([^']+)'""", Pattern.CASE_INSENSITIVE)
    private val META_REFRESH =
        Pattern.compile(
            """<meta[^>]*http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"';>]+)""",
            Pattern.CASE_INSENSITIVE,
        )

    private val SOURCE_WINDOW_ATOB_SINGLE =
        Pattern.compile("""source\s*:\s*window\.atob\('([^']+)'\)""")
    private val SOURCE_WINDOW_ATOB_DOUBLE =
        Pattern.compile("""source\s*:\s*window\.atob\("([^"]+)"\)""")
    private val ATOB_SINGLE = Pattern.compile("""atob\('([^']{20,})'\)""")
    private val ATOB_DOUBLE = Pattern.compile("""atob\("([^"]{20,})"\)""")
    private val DIRECT_M3U8 =
        Pattern.compile("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", Pattern.CASE_INSENSITIVE)
    private val QUOTED_B64_M3U8 =
        Pattern.compile("""["']([A-Za-z0-9+/]{40,}={0,2})["']""")

    private val SKIP_IFRAME_HOSTS = setOf("vuen.link")
    private val SKIP_IFRAME_PREFIXES = listOf("javascript:", "about:blank")

    fun extractIframeCandidates(html: String, baseUrl: String): List<PatternMatch> {
        val ordered = linkedMapOf<String, PatternMatch>()
        fun add(pattern: String, raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            val resolved = resolveUrl(baseUrl, trimmed)
            if (!isUsableIframeUrl(resolved)) return
            ordered.putIfAbsent(resolved, PatternMatch(pattern, resolved))
        }
        forEachGroup(THATFRAME_SRC, html) { add("thatframe_id_src", it) }
        forEachGroup(SRC_THEN_THATFRAME, html) { add("src_thatframe_id", it) }
        forEachGroup(IFRAME_SRC_DOUBLE, html) { add("iframe_src_double", it) }
        forEachGroup(IFRAME_SRC_SINGLE, html) { add("iframe_src_single", it) }
        forEachGroup(META_REFRESH, html) { add("meta_refresh", it) }
        return ordered.values.toList()
    }

    /** Includes stub hosts filtered from [extractIframeCandidates] — for error messages only. */
    fun firstRawIframeSrc(html: String, baseUrl: String): String? {
        val patterns = listOf(THATFRAME_SRC, SRC_THEN_THATFRAME, IFRAME_SRC_DOUBLE, IFRAME_SRC_SINGLE)
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val raw = matcher.group(1)?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    return resolveUrl(baseUrl, raw)
                }
            }
        }
        return null
    }

    fun extractM3u8Url(html: String): PatternMatch? {
        forEachGroup(SOURCE_WINDOW_ATOB_SINGLE, html) { encoded ->
            decodeM3u8Candidate(encoded)?.let { return PatternMatch("source_window_atob_single", it) }
        }
        forEachGroup(SOURCE_WINDOW_ATOB_DOUBLE, html) { encoded ->
            decodeM3u8Candidate(encoded)?.let { return PatternMatch("source_window_atob_double", it) }
        }
        forEachGroup(ATOB_SINGLE, html) { encoded ->
            decodeM3u8Candidate(encoded)?.let { return PatternMatch("atob_single", it) }
        }
        forEachGroup(ATOB_DOUBLE, html) { encoded ->
            decodeM3u8Candidate(encoded)?.let { return PatternMatch("atob_double", it) }
        }
        forEachGroup(DIRECT_M3U8, html) { url ->
            if (url.contains(".m3u8", ignoreCase = true)) {
                return PatternMatch("direct_m3u8_url", url)
            }
        }
        forEachGroup(QUOTED_B64_M3U8, html) { encoded ->
            decodeM3u8Candidate(encoded)?.let { return PatternMatch("quoted_b64_m3u8", it) }
        }
        return null
    }

    fun isEmbedStub(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return host in SKIP_IFRAME_HOSTS
    }

    fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        return URL(URL(base), relative).toString()
    }

    private fun isUsableIframeUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (SKIP_IFRAME_PREFIXES.any { lower.startsWith(it) }) return false
        if (isEmbedStub(url)) return false
        return true
    }

    private fun decodeM3u8Candidate(encoded: String): String? {
        val variants = listOf(encoded, encoded.trim())
        for (candidate in variants) {
            for (decoder in listOf(
                { Base64.getDecoder().decode(candidate) },
                { Base64.getUrlDecoder().decode(candidate) },
            )) {
                try {
                    val decoded = String(decoder())
                    if (decoded.startsWith("http") && decoded.contains(".m3u8", ignoreCase = true)) {
                        return decoded
                    }
                } catch (_: Exception) {
                    // try next decoder
                }
            }
        }
        return null
    }

    private inline fun forEachGroup(pattern: Pattern, html: String, block: (String) -> Unit) {
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val group = matcher.group(1) ?: continue
            block(group)
        }
    }
}
