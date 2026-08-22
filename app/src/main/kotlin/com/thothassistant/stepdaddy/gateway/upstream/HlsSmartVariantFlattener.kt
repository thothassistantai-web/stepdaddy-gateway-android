package com.thothassistant.stepdaddy.gateway.upstream

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TiviMate smart backup mirrors must expose **media** playlists per variant.
 * Serving a nested multivariant master under [MirrorHlsManifest] (especially with
 * `#EXT-X-INDEPENDENT-SEGMENTS` on the outer master) triggers ExoPlayer ParserException.
 */
object HlsSmartVariantFlattener {
    fun isMediaPlaylist(text: String): Boolean =
        text.contains("#EXTINF:", ignoreCase = true)

    fun isMasterPlaylist(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF", ignoreCase = true) && !isMediaPlaylist(text)

    /**
     * When [playlist] is a single-variant master, follow the variant URL and return the
     * media playlist body. Multi-variant masters pick the lightest rendition first.
     */
    fun flattenMasterToMedia(
        playlist: String,
        httpClient: OkHttpClient,
        userAgent: String = NtvCxCdnLiveConfig.CATALOG_USER_AGENT,
        maxDepth: Int = 3,
    ): String {
        var current = normalizeVersionTag(playlist.trim())
        repeat(maxDepth.coerceAtLeast(1)) {
            if (!isMasterPlaylist(current)) return current
            val variantUrl = selectVariantUrl(current) ?: return current
            val next = fetchText(httpClient, variantUrl, userAgent)?.trim().orEmpty()
            if (next.isEmpty()) return current
            current = normalizeVersionTag(next)
        }
        return if (isMediaPlaylist(current)) current else playlist.trim()
    }

    /** TiviMate rejects `#EXT-X-VERSION:03` (leading zero). */
    fun normalizeVersionTag(text: String): String =
        text.replace(Regex("(?m)^#EXT-X-VERSION:0+(\\d+)\\s*$", RegexOption.IGNORE_CASE)) {
            "#EXT-X-VERSION:${it.groupValues[1]}"
        }

    private fun selectVariantUrl(masterText: String): String? {
        val filtered = M3u8Rewriter.rewrite(
            m3u8Text = masterText,
            m3u8Url = "http://127.0.0.1/",
            useProxy = false,
            preferLighterVariant = true,
        )
        return filtered.lines()
            .map { it.trim() }
            .firstOrNull { line -> line.isNotEmpty() && !line.startsWith("#") }
    }

    private fun fetchText(httpClient: OkHttpClient, url: String, userAgent: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }
}
