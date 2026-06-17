package com.nova.stepdaddylivehd.gateway.upstream

import android.util.Log
import com.nova.stepdaddylivehd.gateway.model.UpstreamManifest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

class ResportzParser(
    private val client: OkHttpClient = defaultClient(),
    private val maxEmbedDepth: Int = 2,
) {
    suspend fun fetchManifest(channelId: String, refererBase: String): UpstreamManifest {
        val watchUrl = GatewayConfig.RESPORTZ_STREAM_TEMPLATE.format(channelId)
        Log.d(TAG, "resportz watch $watchUrl")
        val referer = "${refererBase.trimEnd('/')}/"
        val watchHtml = try {
            getText(watchUrl, referer)
        } catch (exc: Exception) {
            throw IllegalStateException("resportz watch failed: ${exc.message}", exc)
        }
        Log.d(TAG, "resportz watch ok (${watchHtml.length} bytes)")
        val iframeCandidates = ResportzHtmlParser.extractIframeCandidates(watchHtml, watchUrl)
        if (iframeCandidates.isEmpty()) {
            val rawIframe = ResportzHtmlParser.firstRawIframeSrc(watchHtml, watchUrl)
            if (rawIframe != null && ResportzHtmlParser.isEmbedStub(rawIframe)) {
                error("embed stub host for channel $channelId ($rawIframe)")
            }
            error("Failed to find iframe source for channel $channelId")
        }
        var lastError: Exception? = null
        for (candidate in iframeCandidates) {
            Log.d(TAG, "resportz iframe pattern=${candidate.pattern} url=${candidate.value}")
            try {
                return resolveFromEmbedPage(
                    channelId = channelId,
                    embedUrl = candidate.value,
                    referer = watchUrl,
                    iframePattern = candidate.pattern,
                    depth = 0,
                )
            } catch (exc: Exception) {
                lastError = exc
                Log.d(TAG, "embed failed pattern=${candidate.pattern}: ${exc.message}")
            }
        }
        throw lastError ?: error("Failed to resolve m3u8 for channel $channelId")
    }

    private suspend fun resolveFromEmbedPage(
        channelId: String,
        embedUrl: String,
        referer: String,
        iframePattern: String,
        depth: Int,
    ): UpstreamManifest {
        if (ResportzHtmlParser.isEmbedStub(embedUrl)) {
            error("embed stub host for channel $channelId ($embedUrl)")
        }
        val sourcePageHtml = getText(embedUrl, referer)
        Log.d(TAG, "resportz embed ok pattern=$iframePattern (${sourcePageHtml.length} bytes)")
        val m3u8Match = ResportzHtmlParser.extractM3u8Url(sourcePageHtml)
        if (m3u8Match != null) {
            Log.d(TAG, "resportz m3u8 pattern=${m3u8Match.pattern} url=${m3u8Match.value}")
            val (resolvedUrl, m3u8Text) = fetchM3u8Text(m3u8Match.value, embedUrl)
            Log.d(TAG, "resportz m3u8 ok (${m3u8Text.length} bytes)")
            return UpstreamManifest(
                playlistText = m3u8Text,
                masterUrl = resolvedUrl,
                refererHost = URL(embedUrl).host,
            )
        }
        if (depth + 1 >= maxEmbedDepth) {
            error("Failed to find encoded m3u8 source for channel $channelId")
        }
        val nested = ResportzHtmlParser.extractIframeCandidates(sourcePageHtml, embedUrl)
        if (nested.isEmpty()) {
            error("Failed to find encoded m3u8 source for channel $channelId")
        }
        var nestedError: Exception? = null
        for (child in nested) {
            Log.d(TAG, "resportz nested iframe depth=${depth + 1} pattern=${child.pattern} url=${child.value}")
            try {
                return resolveFromEmbedPage(
                    channelId = channelId,
                    embedUrl = child.value,
                    referer = embedUrl,
                    iframePattern = child.pattern,
                    depth = depth + 1,
                )
            } catch (exc: Exception) {
                nestedError = exc
            }
        }
        throw nestedError ?: error("Failed to find encoded m3u8 source for channel $channelId")
    }

    private suspend fun fetchM3u8Text(m3u8Url: String, referer: String): Pair<String, String> {
        val candidates = linkedSetOf(m3u8Url)
        if (m3u8Url.contains("index.m3u8")) {
            candidates += m3u8Url.replace("index.m3u8", "tracks-v1a1/mono.m3u8")
            candidates += m3u8Url.replace("index.m3u8", "mono.m3u8")
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return candidate to getText(candidate, referer)
            } catch (exc: Exception) {
                lastError = exc
                Log.d(TAG, "m3u8 fetch failed for $candidate: ${exc.message}")
            }
        }
        throw lastError ?: error("Failed to fetch m3u8")
    }

    private suspend fun getText(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.USER_AGENT)
            .header("Referer", referer)
            .get()
            .build()
        return client.getText(request)
    }

    companion object {
        private const val TAG = "ResportzParser"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(GatewayConfig.UPSTREAM_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(GatewayConfig.UPSTREAM_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(GatewayConfig.UPSTREAM_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(GatewayConfig.UPSTREAM_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
    }
}
