package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull
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
        val daddyFallbacksAttached: Int = 0,
        val probeBudgetExceeded: Boolean = false,
    )

    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: FetchStats,
        val daddyFallbacks: Map<String, List<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>> = emptyMap(),
    )

    /**
     * @param probeBudgetMs when set, caps the whole probe phase; remaining probes are cancelled.
     * @param preferCachedOnBudgetExceed when budget is exceeded, return empty channels so the
     *   caller can retain its adultswim: disk/memory cache (existing empty-fetch retention).
     */
    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        probeBudgetMs: Long? = null,
        preferCachedOnBudgetExceed: Boolean = false,
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val skipDuplicates = importMode.skipsDuplicateRows()
        // Always index Daddy for Smart failover attachments, independent of catalog import mode.
        val daddyIndexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        val daddyFallbacks = mutableMapOf<String, MutableList<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>>()

        val probeResults = runProbes(probeBudgetMs)
        if (probeResults == null) {
            Log.w(
                TAG,
                "adult swim probe budget exceeded" +
                    (probeBudgetMs?.let { " after ${it}ms" } ?: "") +
                    if (preferCachedOnBudgetExceed) " — prefer cache" else "",
            )
            return@withContext FetchOutcome(
                channels = emptyList(),
                stats = FetchStats(
                    catalogRows = AdultSwimStreamsConfig.CATALOG.size,
                    probeBudgetExceeded = true,
                ),
            )
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
            if (SupplementImportMatcher.matchesDaddy(
                    name = row.name,
                    tvgId = row.tvgId,
                    indexes = daddyIndexes,
                    tags = listOf("#us"),
                    countryHint = "US",
                )
            ) {
                val targetId = SupplementImportMatcher.resolveDaddyChannelId(
                    name = row.name,
                    tvgId = row.tvgId,
                    indexes = daddyIndexes,
                    tags = listOf("#us"),
                    countryHint = "US",
                )
                if (targetId != null) {
                    daddyFallbacks.getOrPut(targetId) { mutableListOf() } += com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
                        streamUrl = AdultSwimStreamsConfig.masterPlaylistUrl(row.slug),
                        label = AdultSwimStreamsConfig.PROVIDER_TAG,
                        referer = AdultSwimStreamsConfig.REFERER,
                        origin = AdultSwimStreamsConfig.ORIGIN,
                    )
                }
                if (skipDuplicates) continue
            }
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

        FetchOutcome(
            channels = channels,
            stats = FetchStats(
                catalogRows = AdultSwimStreamsConfig.CATALOG.size,
                probed = probed,
                probeOk = probeOk,
                channelsAfterDedup = channels.size,
                daddyFallbacksAttached = daddyFallbacks.values.sumOf { it.size },
            ),
            daddyFallbacks = daddyFallbacks,
        )
    }

    private suspend fun runProbes(
        probeBudgetMs: Long?,
    ): List<Pair<AdultSwimStreamsConfig.MarathonStream, Boolean>>? {
        val semaphore = Semaphore(AdultSwimStreamsConfig.MAX_CONCURRENT_PROBES)
        suspend fun probeAll(): List<Pair<AdultSwimStreamsConfig.MarathonStream, Boolean>> =
            coroutineScope {
                AdultSwimStreamsConfig.CATALOG.map { row ->
                    async {
                        semaphore.withPermit {
                            row to probeMasterPlaylist(row.slug)
                        }
                    }
                }.awaitAll()
            }
        return if (probeBudgetMs != null && probeBudgetMs > 0L) {
            withTimeoutOrNull(probeBudgetMs) { probeAll() }
        } else {
            probeAll()
        }
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
