package com.nova.stepdaddylivehd.gateway.upstream

import android.util.Log
import com.nova.stepdaddylivehd.gateway.model.UpstreamManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ResportzParser(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetchManifest(channelId: String, refererBase: String): UpstreamManifest =
        withContext(Dispatchers.IO) {
            val watchUrl = GatewayConfig.RESPORTZ_STREAM_TEMPLATE.format(channelId)
            Log.d(TAG, "resportz watch $watchUrl")
            val referer = "${refererBase.trimEnd('/')}/"
            val watchHtml = getText(watchUrl, referer)
            Log.d(TAG, "resportz watch ok (${watchHtml.length} bytes)")
            val iframeSrc = IFRAME_PATTERN.matcher(watchHtml).let { matcher ->
                if (!matcher.find()) {
                    error("Failed to find iframe source for channel $channelId")
                }
                resolveUrl(watchUrl, matcher.group(1) ?: error("empty iframe"))
            }
            Log.d(TAG, "resportz iframe $iframeSrc")
            val sourcePageHtml = getText(iframeSrc, watchUrl)
            Log.d(TAG, "resportz iframe ok (${sourcePageHtml.length} bytes)")
            val encoded = SOURCE_B64_PATTERN.matcher(sourcePageHtml).let { matcher ->
                if (!matcher.find()) {
                    error("Failed to find encoded m3u8 source for channel $channelId")
                }
                matcher.group(1) ?: error("empty encoded source")
            }
            val m3u8Url = String(Base64.getDecoder().decode(encoded))
            Log.d(TAG, "resportz m3u8 url $m3u8Url")
            val m3u8Text = getText(m3u8Url, iframeSrc)
            Log.d(TAG, "resportz m3u8 ok (${m3u8Text.length} bytes)")
            val refererHost = URL(iframeSrc).host
            UpstreamManifest(
                playlistText = m3u8Text,
                masterUrl = m3u8Url,
                refererHost = refererHost,
            )
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

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        val baseUrl = URL(base)
        return URL(baseUrl, relative).toString()
    }

    companion object {
        private const val TAG = "ResportzParser"
        private val IFRAME_PATTERN =
            Pattern.compile("iframe\\s+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        private val SOURCE_B64_PATTERN =
            Pattern.compile("source\\s*:\\s*window\\.atob\\('([^']+)'\\)")

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
