package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
     * @param probeBudgetMs caps the whole probe phase; remaining probes are cancelled and
     *   partial successes are kept. Defaults to [AdultSwimStreamsConfig.PROBE_BUDGET_MS].
     * @param preferCachedOnBudgetExceed when budget is exceeded with zero successes, return empty
     *   channels so the caller can retain its adultswim: disk/memory cache.
     */
    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        probeBudgetMs: Long? = AdultSwimStreamsConfig.PROBE_BUDGET_MS,
        preferCachedOnBudgetExceed: Boolean = false,
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val skipDuplicates = importMode.skipsDuplicateRows()
        // Always index Daddy for Smart failover attachments, independent of catalog import mode.
        val daddyIndexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        val daddyFallbacks = mutableMapOf<String, MutableList<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>>()

        val budget = (probeBudgetMs ?: AdultSwimStreamsConfig.PROBE_BUDGET_MS).coerceAtLeast(1_000L)
        val (probeResults, budgetExceeded) = runProbes(budget)
        if (budgetExceeded) {
            Log.w(
                TAG,
                "adult swim probe budget exceeded after ${budget}ms " +
                    "(partial=${probeResults.size}/${AdultSwimStreamsConfig.CATALOG.size})" +
                    if (preferCachedOnBudgetExceed && probeResults.none { it.second }) {
                        " — prefer cache"
                    } else {
                        ""
                    },
            )
        }

        if (preferCachedOnBudgetExceed && budgetExceeded && probeResults.none { it.second }) {
            return@withContext FetchOutcome(
                channels = emptyList(),
                stats = FetchStats(
                    catalogRows = AdultSwimStreamsConfig.CATALOG.size,
                    probed = probeResults.size,
                    probeOk = 0,
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
                    daddyFallbacks.getOrPut(targetId) { mutableListOf() } +=
                        com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
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
                probeBudgetExceeded = budgetExceeded,
            ),
            daddyFallbacks = daddyFallbacks,
        )
    }

    /**
     * Runs probes under a hard wall-clock budget. OkHttp calls are cancelled on timeout so
     * coroutine cancellation is not blocked by [okhttp3.Call.execute].
     */
    private suspend fun runProbes(
        probeBudgetMs: Long,
    ): Pair<List<Pair<AdultSwimStreamsConfig.MarathonStream, Boolean>>, Boolean> {
        val probeClient = httpClient.newBuilder()
            .retryOnConnectionFailure(false)
            .connectTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)
            .build()
        val completed = ConcurrentHashMap<String, Pair<AdultSwimStreamsConfig.MarathonStream, Boolean>>()
        val semaphore = Semaphore(AdultSwimStreamsConfig.MAX_CONCURRENT_PROBES)
        val finishedAll = withTimeoutOrNull(probeBudgetMs) {
            coroutineScope {
                AdultSwimStreamsConfig.CATALOG.map { row ->
                    async {
                        semaphore.withPermit {
                            ensureActive()
                            val ok = probeMasterPlaylist(row.slug, probeClient)
                            completed[row.slug] = row to ok
                        }
                    }
                }.awaitAll()
            }
            true
        } != null
        val ordered = AdultSwimStreamsConfig.CATALOG.mapNotNull { row -> completed[row.slug] }
        return ordered to !finishedAll
    }

    private suspend fun probeMasterPlaylist(slug: String, client: OkHttpClient): Boolean {
        val url = AdultSwimStreamsConfig.masterPlaylistUrl(slug)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NtvCxCdnLiveConfig.CATALOG_USER_AGENT)
            .header("Referer", AdultSwimStreamsConfig.REFERER)
            .header("Origin", AdultSwimStreamsConfig.ORIGIN)
            .get()
            .build()
        return try {
            val body = awaitCallBody(client, request) ?: return false
            val trimmed = body.trimStart()
            trimmed.startsWith("#EXTM3U") && trimmed.contains("#EXT-X-STREAM-INF")
        } catch (exc: Exception) {
            Log.d(TAG, "probe error $slug: ${exc.message}")
            false
        }
    }

    private suspend fun awaitCallBody(client: OkHttpClient, request: Request): String? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!cont.isActive) return
                        if (!resp.isSuccessful) {
                            cont.resume(null)
                            return
                        }
                        cont.resume(resp.body?.string())
                    }
                }
            })
        }

    companion object {
        private const val TAG = "AdultSwimStreamsSource"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(AdultSwimStreamsConfig.PROBE_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
