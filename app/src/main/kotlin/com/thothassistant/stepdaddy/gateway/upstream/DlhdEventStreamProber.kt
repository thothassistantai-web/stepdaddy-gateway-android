package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Probes dlhd-event upstream playability using [DlhdEventStreamResolver] for tv2 embeds
 * and an injected DaddyLive resolve hook for numeric `tv|` event streams.
 */
class DlhdEventStreamProber(
    private val resolver: DlhdEventStreamResolver = DlhdEventStreamResolver(),
    private val httpClient: OkHttpClient = ResportzParser.defaultClient(),
) {
    suspend fun probe(
        channel: SupplementChannel,
        tvStreamProbe: suspend (channelId: String) -> Boolean,
    ): DlhdEventStreamHealth.ProbeResult {
        val key = channel.dlhdEventStreamKey?.trim().orEmpty()
        if (key.isEmpty()) {
            return DlhdEventStreamHealth.ProbeResult.unhealthy("missing_stream_key")
        }
        return when {
            key.startsWith("tv2|", ignoreCase = true) -> probeTv2Embed(channel, key)
            key.startsWith("tv|", ignoreCase = true) -> probeNumericTv(key, tvStreamProbe)
            else -> DlhdEventStreamHealth.ProbeResult.unknown("unsupported_stream_key")
        }
    }

    private fun probeTv2Embed(
        channel: SupplementChannel,
        streamKey: String,
    ): DlhdEventStreamHealth.ProbeResult {
        val referer = channel.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: DlhdEventStreamResolver.EMBED_REFERER
        val manifestUrl = resolver.resolveManifestUrl(streamKey, referer)
            ?: return DlhdEventStreamHealth.ProbeResult.unhealthy("manifest_unresolved")
        val manifestText = resolver.fetchManifestText(manifestUrl, referer)
            ?: return DlhdEventStreamHealth.ProbeResult.unhealthy("manifest_fetch_failed")
        if (!manifestText.trimStart().startsWith("#EXTM3U")) {
            return DlhdEventStreamHealth.ProbeResult.unhealthy("invalid_manifest")
        }
        return probeManifestChain(manifestUrl, manifestText, referer)
    }

    private suspend fun probeNumericTv(
        streamKey: String,
        tvStreamProbe: suspend (channelId: String) -> Boolean,
    ): DlhdEventStreamHealth.ProbeResult {
        val channelId = streamKey.substringAfter("|").trim()
        if (channelId.isEmpty()) {
            return DlhdEventStreamHealth.ProbeResult.unhealthy("missing_tv_channel_id")
        }
        return if (tvStreamProbe(channelId)) {
            DlhdEventStreamHealth.ProbeResult.healthy()
        } else {
            DlhdEventStreamHealth.ProbeResult.unhealthy("tv_resolve_failed")
        }
    }

    private fun probeManifestChain(
        manifestUrl: String,
        manifestText: String,
        referer: String,
    ): DlhdEventStreamHealth.ProbeResult {
        val client = probeClient()
        if (HlsManifestProbe.isMasterPlaylist(manifestText)) {
            val variantUrl = HlsManifestProbe.firstMediaUrl(manifestText, manifestUrl)
                ?: return DlhdEventStreamHealth.ProbeResult.unhealthy("no_variant_url")
            val variantText = fetchText(variantUrl, referer, client)
                ?: return DlhdEventStreamHealth.ProbeResult.unhealthy("variant_fetch_failed")
            return probeMediaPlaylist(variantUrl, variantText, referer, client)
        }
        return probeMediaPlaylist(manifestUrl, manifestText, referer, client)
    }

    private fun probeMediaPlaylist(
        manifestUrl: String,
        manifestText: String,
        referer: String,
        client: OkHttpClient,
    ): DlhdEventStreamHealth.ProbeResult {
        val targetUrl = HlsManifestProbe.firstSegmentUrl(manifestText, manifestUrl)
            ?: HlsManifestProbe.firstMediaUrl(manifestText, manifestUrl)
            ?: return DlhdEventStreamHealth.ProbeResult.unhealthy("no_media_url")
        return if (isReachable(targetUrl, referer, client)) {
            DlhdEventStreamHealth.ProbeResult.healthy()
        } else {
            DlhdEventStreamHealth.ProbeResult.unhealthy("segment_unreachable")
        }
    }

    private fun isReachable(url: String, referer: String, client: OkHttpClient): Boolean {
        val head = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .header("Origin", referer.trimEnd('/'))
            .head()
            .build()
        runCatching {
            client.newCall(head).execute().use { response ->
                if (response.isSuccessful) return true
                if (response.code == 405 || response.code == 501) {
                    return getReachable(url, referer, client)
                }
            }
        }
        return getReachable(url, referer, client)
    }

    private fun getReachable(url: String, referer: String, client: OkHttpClient): Boolean {
        val get = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .header("Origin", referer.trimEnd('/'))
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return runCatching {
            client.newCall(get).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        }.getOrDefault(false)
    }

    private fun fetchText(url: String, referer: String, client: OkHttpClient): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .header("Origin", referer.trimEnd('/'))
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun probeClient(): OkHttpClient =
        httpClient.newBuilder()
            .readTimeout(SupplementConfig.DLHD_EVENT_HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SupplementConfig.DLHD_EVENT_HEALTH_PROBE_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
            .build()
}
