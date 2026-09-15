package com.thothassistant.stepdaddy.gateway.upstream

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

    /**
     * Player hosts that typically carry `window._econfig` (or similar) and should be
     * followed before generic / nontongo hubs so embed depth is not burned on dead ends.
     */
    private val PRIORITY_PLAYER_HOST_TOKENS =
        listOf(
            "assetrage",
            "tiestep",
            "dlive.sx",
            "cdn.dlive",
            "premiumtv",
            "jimpenopisonline",
            "xameleon",
            "castaddylog",
        )

    private val DEPRIORITIZED_HUB_TOKENS =
        listOf(
            "nontongo",
            "vuen.link",
        )

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
        return prioritizePatternMatches(ordered.values.toList())
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
        EconfigDecoder.extractStreamUrl(html)?.let {
            return PatternMatch("econfig_stream_url", it)
        }
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

    /**
     * Hub URLs from modern DaddyLive watch pages (`data-tv-daddy-urls`, nontongo view, etc.).
     */
    fun extractPlayerHubUrls(html: String, channelId: String, pageUrl: String): List<String> {
        val hubs = linkedSetOf<String>()
        val cid = channelId.trim()
        fun add(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            val resolved =
                when {
                    trimmed.startsWith("//") -> "https:$trimmed"
                    trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                    else -> resolveUrl(pageUrl.ifBlank { "https://daddylive.li/" }, trimmed)
                }
            if (resolved.startsWith("http")) {
                hubs += resolved
            }
        }
        // Prefer known player iframes (assetrage on dlive, etc.) before daddy-url / nontongo hubs.
        for (iframe in extractIframeCandidates(html, pageUrl)) {
            add(iframe.value)
        }
        val daddyUrls =
            Pattern.compile("""data-tv-daddy-urls="([^"]+)"""", Pattern.CASE_INSENSITIVE)
        val matcher = daddyUrls.matcher(html)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            val decoded =
                raw.replace("&quot;", "\"")
                    .replace("&#34;", "\"")
                    .replace("\\/", "/")
            try {
                val arr = org.json.JSONArray(decoded)
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            } catch (_: Exception) {
                // JVM unit tests stub org.json — fall back to quoted URL scrape.
                val quoted =
                    Pattern.compile("\"(https?:\\\\?/\\\\?/[^\\\\\"]+|//[^\\\\\"]+|/[^\"]+)\"")
                val qm = quoted.matcher(decoded)
                while (qm.find()) {
                    val rawUrl = qm.group(1)?.replace("\\/", "/") ?: continue
                    add(rawUrl)
                }
            }
        }
        if (html.contains("nontongo.win/livetv", ignoreCase = true) || html.contains("/livetv/$cid")) {
            add("https://www.nontongo.win/livetv/view/$cid")
            add("https://www.nontongo.win/livetv/$cid")
        }
        return prioritizeHubUrls(hubs.toList())
    }

    /**
     * Order embed/hub candidates so known player hosts (assetrage, dlive, …) are tried
     * before nontongo / generic hubs at each recursion depth.
     */
    fun prioritizeHubUrls(urls: Collection<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val unique = linkedSetOf<String>().apply { addAll(urls) }
        return unique.sortedWith(compareBy({ hubPriorityRank(it) }, { unique.indexOf(it) }))
    }

    fun prioritizePatternMatches(matches: List<PatternMatch>): List<PatternMatch> {
        if (matches.size <= 1) return matches
        return matches.sortedWith(
            compareBy({ hubPriorityRank(it.value) }, { matches.indexOf(it) }),
        )
    }

    fun hubPriorityRank(url: String): Int {
        val lower = url.lowercase()
        val preferredIndex = PRIORITY_PLAYER_HOST_TOKENS.indexOfFirst { lower.contains(it) }
        if (preferredIndex >= 0) {
            return preferredIndex
        }
        if (DEPRIORITIZED_HUB_TOKENS.any { lower.contains(it) }) {
            return 1_000
        }
        return 100
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
        // Skip JS string-concat templates scraped from inline scripts (e.g. '+domain+'/player/…).
        if ("'+" in url || "\"+" in url || "+domain+" in lower || "+channelid+" in lower) {
            return false
        }
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
