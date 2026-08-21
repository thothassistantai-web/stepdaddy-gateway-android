package com.thothassistant.stepdaddy.gateway.relay

import android.content.Context
import android.util.Log
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
 * Lightweight stream probe + failure cooldown for VOD relay candidates.
 */
class VodCatalogRelayProbe(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class ProbeOutcome(
        val movieStreams: Map<Int, List<VodRelayWorkingStream>>,
        val showStreams: Map<String, List<VodRelayWorkingStream>>,
        val probed: Int,
        val probeOk: Int,
        val deadPruned: Int,
    )

    suspend fun probeManifest(manifest: VodCatalogRelayManifest): ProbeOutcome = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_CONCURRENT)
        var probed = 0
        var probeOk = 0
        var deadPruned = 0
        val movieStreams = mutableMapOf<Int, MutableList<VodRelayWorkingStream>>()
        val showStreams = mutableMapOf<String, MutableList<VodRelayWorkingStream>>()

        coroutineScope {
            val movieJobs = manifest.movies.filter { it.tmdbId > 0 }.flatMap { movie ->
                movie.streams.map { stream ->
                    async {
                        semaphore.withPermit {
                            val ok = probeUrl(stream.url, stream.referer)
                            ProbeItem.Movie(movie.tmdbId, stream, ok)
                        }
                    }
                }
            }
            val showJobs = manifest.shows.filter { it.tmdbId > 0 }.flatMap { show ->
                show.streams.map { stream ->
                    async {
                        semaphore.withPermit {
                            val ok = probeUrl(stream.url, stream.referer)
                            ProbeItem.Show(show.tmdbId, show.season, show.episode, stream, ok)
                        }
                    }
                }
            }
            (movieJobs + showJobs).awaitAll().forEach { item ->
                probed++
                when (item) {
                    is ProbeItem.Movie -> {
                        if (item.ok) {
                            probeOk++
                            clearFailure(item.stream.url)
                            movieStreams.getOrPut(item.tmdbId) { mutableListOf() } +=
                                item.stream.toWorking()
                        } else {
                            deadPruned++
                            recordFailure(item.stream.url)
                        }
                    }
                    is ProbeItem.Show -> {
                        if (item.ok) {
                            probeOk++
                            clearFailure(item.stream.url)
                            val key = "${item.showTmdbId}:${item.season}:${item.episode}"
                            showStreams.getOrPut(key) { mutableListOf() } += item.stream.toWorking()
                        } else {
                            deadPruned++
                            recordFailure(item.stream.url)
                        }
                    }
                }
            }
        }

        // Prefer streams that are not in cooldown when re-applying without re-probe.
        ProbeOutcome(
            movieStreams = movieStreams.mapValues { (_, list) ->
                list.sortedByDescending { it.qualityRank() }
            },
            showStreams = showStreams.mapValues { (_, list) ->
                list.sortedByDescending { it.qualityRank() }
            },
            probed = probed,
            probeOk = probeOk,
            deadPruned = deadPruned,
        )
    }

    fun isInCooldown(url: String): Boolean {
        val until = prefs.getLong(cooldownKey(url), 0L)
        return System.currentTimeMillis() < until
    }

    private fun probeUrl(url: String, referer: String?): Boolean {
        if (isInCooldown(url)) return false
        val failures = prefs.getInt(failKey(url), 0)
        if (failures >= MAX_FAILURES_BEFORE_SKIP) {
            val until = prefs.getLong(cooldownKey(url), 0L)
            if (until > System.currentTimeMillis()) return false
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
            }
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val bodyPeek = response.body?.source()?.let { source ->
                    source.request(512)
                    source.buffer.clone().readUtf8(minOf(512, source.buffer.size))
                }.orEmpty()
                when {
                    contentType.contains("mpegurl") || contentType.contains("m3u8") -> true
                    bodyPeek.trimStart().startsWith("#EXTM3U") -> true
                    contentType.contains("mp4") || contentType.contains("video") -> true
                    contentType.contains("octet-stream") && bodyPeek.isNotEmpty() -> true
                    response.code in 200..299 && bodyPeek.isNotEmpty() -> true
                    else -> response.code in 200..299
                }
            }
        }.onFailure { Log.d(TAG, "probe failed $url: ${it.message}") }
            .getOrDefault(false)
    }

    private fun recordFailure(url: String) {
        val next = prefs.getInt(failKey(url), 0) + 1
        val editor = prefs.edit().putInt(failKey(url), next)
        if (next >= MAX_FAILURES_BEFORE_SKIP) {
            editor.putLong(cooldownKey(url), System.currentTimeMillis() + COOLDOWN_MS)
        }
        editor.apply()
    }

    private fun clearFailure(url: String) {
        prefs.edit()
            .remove(failKey(url))
            .remove(cooldownKey(url))
            .apply()
    }

    private fun failKey(url: String) = "fail:${url.hashCode()}"
    private fun cooldownKey(url: String) = "cool:${url.hashCode()}"

    private fun VodRelayStream.toWorking() = VodRelayWorkingStream(
        url = url,
        referer = referer,
        quality = quality,
        label = label,
    )

    private fun VodRelayWorkingStream.qualityRank(): Int {
        val q = quality?.lowercase().orEmpty()
        return when {
            "2160" in q || "4k" in q -> 400
            "1080" in q -> 300
            "720" in q -> 200
            "480" in q -> 100
            else -> 50
        }
    }

    private sealed class ProbeItem {
        data class Movie(val tmdbId: Int, val stream: VodRelayStream, val ok: Boolean) : ProbeItem()
        data class Show(
            val showTmdbId: Int,
            val season: Int,
            val episode: Int,
            val stream: VodRelayStream,
            val ok: Boolean,
        ) : ProbeItem()
    }

    companion object {
        private const val TAG = "VodCatalogRelayProbe"
        private const val PREFS_NAME = "vod_catalog_relay_probe"
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val MAX_CONCURRENT = 2
        private const val MAX_FAILURES_BEFORE_SKIP = 3
        private const val COOLDOWN_MS = 30 * 60 * 1000L
        private const val PROBE_TIMEOUT_MS = 8_000L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PROBE_TIMEOUT_MS + 3_000L, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()
    }
}
