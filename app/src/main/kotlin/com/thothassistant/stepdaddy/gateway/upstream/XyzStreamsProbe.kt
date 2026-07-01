package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Validates xyzstreams 247v2 Sling manifests before publishing a channel row. */
object XyzStreamsProbe {
    fun isLiveManifest(
        streamId: String,
        proId: String = XyzStreamsConfig.PRO_ID_SLING,
        httpClient: OkHttpClient = defaultClient(),
    ): Boolean {
        val url = "https://${XyzStreamsConfig.GATEWAY_STREAM_BASE}/?stream_id=$streamId&pro_id=$proId&index.m3u8"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .header("Referer", XyzStreamsConfig.REFERER)
            .header("Origin", XyzStreamsConfig.ORIGIN)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body?.string()?.trimStart().orEmpty()
                body.startsWith("#EXTM3U")
            }
        }.getOrDefault(false)
    }

    /** Build Sling stream_id guesses from a TV Guide callsign or network label. */
    fun candidateStreamIds(epgKey: String): List<String> {
        val raw = epgKey.trim()
        if (raw.isEmpty()) return emptyList()
        val upper = raw.uppercase()
        val out = linkedSetOf<String>()

        XyzStreamsCatalog.KnownEpgStreamIds[upper]?.let { out += it }

        val alpha = upper.replace(Regex("[^A-Z0-9]"), "")
        if (alpha.isNotEmpty()) {
            out += alpha.lowercase()
            if (alpha.endsWith("HD")) {
                out += alpha.removeSuffix("HD").lowercase()
            }
        }

        val slug = upper
            .replace("-DT", "")
            .replace(Regex("HD$"), "")
            .lowercase()
        if (slug.isNotEmpty()) out += slug

        XyzStreamsCatalog.StreamIdAliases[upper]?.let { out += it }

        return out.filter { it.isNotEmpty() && it.length <= 40 }.distinct()
    }

    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
}
