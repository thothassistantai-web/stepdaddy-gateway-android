package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches Adult Swim marathon HLS streams; only publishes slugs that pass a live probe.
 */
class AdultSwimStreamsSource(
    private val httpClient: OkHttpClient,
) {
    data class FetchStats(
        val catalogRows: Int = 0,
        val probed: Int = 0,
        val probeOk: Int = 0,
        val channelsAfterDedup: Int = 0,
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
    ): Pair<List<SupplementChannel>, FetchStats> = withContext(Dispatchers.IO) {
        val skipDuplicates = importMode == SupplementImportMode.SKIP_DUPLICATES
        val daddyNormNames = if (skipDuplicates) {
            daddyChannels
                .map { EpgChannelMapper.normalizeName(it.name) }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            emptySet()
        }

        val semaphore = Semaphore(AdultSwimStreamsConfig.MAX_CONCURRENT_PROBES)
        val probeResults = coroutineScope {
            AdultSwimStreamsConfig.CATALOG.map { row ->
                async {
                    semaphore.withPermit {
                        row to probeMasterPlaylist(row.slug)
                    }
                }
            }.awaitAll()
        }

        var probed = 0
        var probeOk = 0
        val channels = mutableListOf<SupplementChannel>()
        for ((row, ok) in probeResults) {
            probed++
            if (!ok) {
                Log.d(TAG, "adult swim probe failed: ${row.slug}")
                continue
            }
            probeOk++
            val norm = EpgChannelMapper.normalizeName(row.name)
            if (skipDuplicates && norm in daddyNormNames) continue
            channels += SupplementChannel(
                id = "adultswim:${row.slug}",
                name = row.name,
                tvgId = row.tvgId,
                logo = row.logo,
                groupTitle = AdultSwimStreamsConfig.GROUP_TITLE,
                streamUrl = AdultSwimStreamsConfig.masterPlaylistUrl(row.slug),
                providerTag = AdultSwimStreamsConfig.PROVIDER_TAG,
                referer = AdultSwimStreamsConfig.REFERER,
                origin = AdultSwimStreamsConfig.ORIGIN,
                tags = listOf("#animation", "#entertainment", "#us"),
            )
        }

        channels to FetchStats(
            catalogRows = AdultSwimStreamsConfig.CATALOG.size,
            probed = probed,
            probeOk = probeOk,
            channelsAfterDedup = channels.size,
        )
    }

    private fun probeMasterPlaylist(slug: String): Boolean {
        val url = AdultSwimStreamsConfig.masterPlaylistUrl(slug)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NtvCxCdnLiveConfig.CATALOG_USER_AGENT)
            .header("Referer", AdultSwimStreamsConfig.REFERER)
            .header("Origin", AdultSwimStreamsConfig.ORIGIN)
            .get()
            .build()
        return runCatching {
            val client = httpClient.newBuilder()
                .readTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body?.string()?.trimStart().orEmpty()
                body.startsWith("#EXTM3U") && body.contains("#EXT-X-STREAM-INF")
            }
        }.getOrElse { exc ->
            Log.d(TAG, "probe error $slug: ${exc.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AdultSwimStreamsSource"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
