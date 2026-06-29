package com.thothassistant.stepdaddy.gateway.upstream

import okhttp3.OkHttpClient
import okhttp3.Request

/** Resolves DaddyLive tv2 embed streams (`embed.st`) at play time. */
class DlhdEventStreamResolver(
    private val httpClient: OkHttpClient = ResportzParser.defaultClient(),
    private val manifestUrlOverride: ((streamKey: String) -> String?)? = null,
) {
    fun resolveManifestUrl(streamKey: String, referer: String = EMBED_REFERER): String? {
        manifestUrlOverride?.invoke(streamKey)?.let { return it }
        val parts = streamKey.split("|", limit = 2)
        if (parts.size != 2) return null
        return when (parts[0].lowercase()) {
            "tv2" -> resolveTv2Embed(parts[1].trim(), referer)
            else -> null
        }
    }

    fun fetchManifestText(manifestUrl: String, referer: String): String? {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .header("Origin", referer.trimEnd('/'))
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun resolveTv2Embed(channelPath: String, referer: String): String? {
        if (channelPath.isEmpty()) return null
        val embedUrl = "$EMBED_BASE/$channelPath"
        val html = fetchText(embedUrl, referer) ?: return null
        return ResportzHtmlParser.extractM3u8Url(html)?.value
    }

    private fun fetchText(url: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        private const val EMBED_BASE = "https://embed.st/embed"
        const val EMBED_REFERER = "https://daddylive.li/"
    }
}
