package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ResportzParser(
    private val client: OkHttpClient = defaultClient(),
    private val maxEmbedDepth: Int = 2,
    private val mirrorLatencyTracker: MirrorLatencyTracker? = null,
) {
    private val dlhdPathFailureCounts = ConcurrentHashMap<String, Int>()
    private val dlhdPathCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val dlhdHostFailureCounts = ConcurrentHashMap<String, Int>()
    private val dlhdHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val resportzHostFailureCounts = ConcurrentHashMap<String, Int>()
    private val resportzHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    suspend fun fetchManifest(channelId: String, refererBase: String): UpstreamManifest {
        val referer = "${refererBase.trimEnd('/')}/"
        val candidates = watchUrlCandidates(channelId)
        val dlhdCandidates = candidates.filter { isDlhdRelayUrl(it) }
        val otherCandidates = candidates.filterNot { isDlhdRelayUrl(it) }

        if (dlhdCandidates.isNotEmpty()) {
            val raced = raceDlhdWatchUrls(channelId, dlhdCandidates, referer)
            if (raced != null) {
                return raced
            }
        }

        var lastError: Exception? = null
        for (watchUrl in otherCandidates) {
            try {
                val manifest = fetchManifestFromWatchPage(channelId, watchUrl, referer)
                markWatchHostSuccess(watchUrl)
                return manifest
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                lastError = exc
                markWatchHostFailure(watchUrl)
                Log.d(TAG, "watch failed $watchUrl: ${exc.message}")
            }
        }
        throw IllegalStateException(
            "resportz watch failed: ${lastError?.message ?: "no watch URLs"}",
            lastError,
        )
    }

    private suspend fun raceDlhdWatchUrls(
        channelId: String,
        candidates: List<String>,
        referer: String,
    ): UpstreamManifest? {
        val ordered = mirrorLatencyTracker?.orderedDlhdPaths(
            GatewayConfig.DLHD_PK_STREAM_PATHS.filter { path ->
                candidates.any { url -> url.contains("/$path/") }
            }.ifEmpty { GatewayConfig.DLHD_PK_STREAM_PATHS },
        ) ?: GatewayConfig.DLHD_PK_STREAM_PATHS
        val eligiblePaths = ordered.filterNot { isDlhdPathCoolingDown(it) }
        val activePaths = eligiblePaths.ifEmpty { ordered }

        val toRace = activePaths.mapNotNull { path ->
            candidates.firstOrNull { it.contains("/$path/") }
        }.distinct().take(GatewayConfig.DLHD_PK_PARALLEL_PROBE_COUNT)

        if (toRace.isEmpty()) return null
        if (toRace.size == 1) {
            val watchUrl = toRace.first()
            val path = dlhdPathFromUrl(watchUrl)
            val startedAt = System.nanoTime()
            return runCatching {
                val manifest = fetchManifestFromWatchPage(channelId, watchUrl, referer)
                val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                        path?.let { mirrorLatencyTracker?.recordDlhdPathSuccess(it, latencyMs) }
                        path?.let { markDlhdPathSuccess(it) }
                        markWatchHostSuccess(watchUrl)
                manifest
            }.onFailure {
                path?.let { mirrorLatencyTracker?.recordDlhdPathFailure(it) }
                path?.let { markDlhdPathFailure(it) }
                        markWatchHostFailure(watchUrl)
            }.getOrNull()
        }

        return coroutineScope {
            val winner = CompletableDeferred<UpstreamManifest>()
            val jobs = toRace.map { watchUrl ->
                launch {
                    val path = dlhdPathFromUrl(watchUrl)
                    val startedAt = System.nanoTime()
                    try {
                        val manifest = fetchManifestFromWatchPage(channelId, watchUrl, referer)
                        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                        path?.let { mirrorLatencyTracker?.recordDlhdPathSuccess(it, latencyMs) }
                        path?.let { markDlhdPathSuccess(it) }
                        markWatchHostSuccess(watchUrl)
                        if (!winner.isCompleted) {
                            winner.complete(manifest)
                        }
                    } catch (exc: CancellationException) {
                        throw exc
                    } catch (exc: Exception) {
                        path?.let { mirrorLatencyTracker?.recordDlhdPathFailure(it) }
                        path?.let { markDlhdPathFailure(it) }
                        markWatchHostFailure(watchUrl)
                        Log.d(TAG, "dlhd race failed $watchUrl: ${exc.message}")
                    }
                }
            }
            val result = withTimeoutOrNull(GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS) {
                runCatching { winner.await() }.getOrNull()
            }
            jobs.forEach { it.cancel() }
            result
        }
    }

    private fun watchUrlCandidates(channelId: String): List<String> {
        val orderedPaths = mirrorLatencyTracker?.orderedDlhdPaths(GatewayConfig.DLHD_PK_STREAM_PATHS)
            ?: GatewayConfig.DLHD_PK_STREAM_PATHS
        val eligiblePaths = orderedPaths.filterNot { isDlhdPathCoolingDown(it) }
        val activePaths = eligiblePaths.ifEmpty { orderedPaths }
        val ordered = linkedSetOf<String>()
        for (host in orderedDlhdRelayHosts()) {
            val base = host.trimEnd('/')
            for (path in activePaths) {
                ordered += "$base/$path/stream-$channelId.php"
            }
        }
        for (host in orderedResportzHosts()) {
            val base = host.trimEnd('/')
            ordered += base + GatewayConfig.RESPORTZ_STREAM_PATH.format(channelId)
        }
        return ordered.toList()
    }

    private fun isDlhdRelayUrl(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return dlhdRelayHosts().any { hostMatches(host, it) }
    }

    private fun dlhdPathFromUrl(url: String): String? =
        GatewayConfig.DLHD_PK_STREAM_PATHS.firstOrNull { path -> url.contains("/$path/") }

    private fun isDlhdPathCoolingDown(path: String): Boolean {
        val retryAt = dlhdPathCooldownUntilMs[path] ?: return false
        if (System.currentTimeMillis() >= retryAt) {
            dlhdPathCooldownUntilMs.remove(path)
            dlhdPathFailureCounts.remove(path)
            return false
        }
        return true
    }

    private fun markDlhdPathFailure(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        val nextCount = (dlhdPathFailureCounts[trimmed] ?: 0) + 1
        dlhdPathFailureCounts[trimmed] = nextCount
        val backoff = min(
            GatewayConfig.DLHD_PATH_COOLDOWN_BASE_MS * (1L shl min(nextCount - 1, 4)),
            GatewayConfig.DLHD_PATH_COOLDOWN_MAX_MS,
        )
        dlhdPathCooldownUntilMs[trimmed] = now + backoff
    }

    private fun markDlhdPathSuccess(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        dlhdPathFailureCounts.remove(trimmed)
        dlhdPathCooldownUntilMs.remove(trimmed)
    }

    private fun orderedDlhdRelayHosts(): List<String> {
        val hosts = GatewayConfig.DLHD_RELAY_HOSTS
        val eligible = hosts.filterNot { isDlhdHostCoolingDown(it) }
        return eligible.ifEmpty { hosts }
    }

    private fun orderedResportzHosts(): List<String> {
        val hosts = GatewayConfig.RESPORTZ_WATCH_HOSTS
        val eligible = hosts.filterNot { isResportzHostCoolingDown(it) }
        return eligible.ifEmpty { hosts }
    }

    private fun isDlhdHostCoolingDown(host: String): Boolean =
        isWatchHostCoolingDown(host, dlhdHostCooldownUntilMs, dlhdHostFailureCounts)

    private fun isResportzHostCoolingDown(host: String): Boolean =
        isWatchHostCoolingDown(host, resportzHostCooldownUntilMs, resportzHostFailureCounts)

    private fun isWatchHostCoolingDown(
        host: String,
        cooldowns: ConcurrentHashMap<String, Long>,
        failures: ConcurrentHashMap<String, Int>,
    ): Boolean {
        val key = hostKey(host)
        val retryAt = cooldowns[key] ?: return false
        if (System.currentTimeMillis() >= retryAt) {
            cooldowns.remove(key)
            failures.remove(key)
            return false
        }
        return true
    }

    private fun markWatchHostFailure(watchUrl: String) {
        val host = runCatching { URL(watchUrl).host.lowercase() }.getOrNull() ?: return
        when {
            isDlhdRelayHost(host) ->
                markHostFailure(
                    host,
                    dlhdHostFailureCounts,
                    dlhdHostCooldownUntilMs,
                    GatewayConfig.DLHD_HOST_COOLDOWN_BASE_MS,
                    GatewayConfig.DLHD_HOST_COOLDOWN_MAX_MS,
                )
            isResportzHost(host) ->
                markHostFailure(
                    host,
                    resportzHostFailureCounts,
                    resportzHostCooldownUntilMs,
                    GatewayConfig.RESPORTZ_HOST_COOLDOWN_BASE_MS,
                    GatewayConfig.RESPORTZ_HOST_COOLDOWN_MAX_MS,
                )
        }
    }

    private fun markWatchHostSuccess(watchUrl: String) {
        val host = runCatching { URL(watchUrl).host.lowercase() }.getOrNull() ?: return
        when {
            isDlhdRelayHost(host) -> {
                val key = hostKey(host)
                dlhdHostFailureCounts.remove(key)
                dlhdHostCooldownUntilMs.remove(key)
            }
            isResportzHost(host) -> {
                val key = hostKey(host)
                resportzHostFailureCounts.remove(key)
                resportzHostCooldownUntilMs.remove(key)
            }
        }
    }

    private fun markHostFailure(
        host: String,
        failures: ConcurrentHashMap<String, Int>,
        cooldowns: ConcurrentHashMap<String, Long>,
        baseMs: Long,
        maxMs: Long,
    ) {
        val key = hostKey(host)
        val now = System.currentTimeMillis()
        val nextCount = (failures[key] ?: 0) + 1
        failures[key] = nextCount
        val backoff = min(baseMs * (1L shl min(nextCount - 1, 4)), maxMs)
        cooldowns[key] = now + backoff
    }

    private fun hostKey(hostOrUrl: String): String =
        runCatching { URL(hostOrUrl).host.lowercase() }.getOrNull()
            ?: hostOrUrl.trim().lowercase()

    private fun isDlhdRelayHost(host: String): Boolean =
        dlhdRelayHosts().any { hostMatches(host, it) }

    private fun isResportzHost(host: String): Boolean =
        resportzHosts().any { hostMatches(host, it) }

    private fun dlhdRelayHosts(): Set<String> =
        GatewayConfig.DLHD_RELAY_HOSTS.mapNotNull { hostFromBase(it) }.toSet()

    private fun resportzHosts(): Set<String> =
        GatewayConfig.RESPORTZ_WATCH_HOSTS.mapNotNull { hostFromBase(it) }.toSet()

    private fun hostFromBase(baseUrl: String): String? =
        runCatching { URL(baseUrl).host.lowercase() }.getOrNull()

    private fun hostMatches(host: String, token: String): Boolean =
        host == token || host.endsWith(".$token")

    private suspend fun fetchManifestFromWatchPage(
        channelId: String,
        watchUrl: String,
        referer: String,
    ): UpstreamManifest {
        Log.d(TAG, "resportz watch $watchUrl")
        val watchHtml = getText(watchUrl, referer)
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
            val resolvedM3u8 = resolveM3u8Url(m3u8Match.value, embedUrl)
            val (resolvedUrl, m3u8Text) = fetchM3u8Text(resolvedM3u8, embedUrl)
            Log.d(TAG, "resportz m3u8 ok (${m3u8Text.length} bytes)")
            return UpstreamManifest(
                playlistText = m3u8Text,
                masterUrl = resolvedUrl,
                // Keep the full embed URL so referer-sensitive hosts (xameleon) pass validation.
                refererHost = embedUrl,
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

    private fun resolveM3u8Url(m3u8Url: String, baseUrl: String): String =
        if (m3u8Url.startsWith("http://") || m3u8Url.startsWith("https://")) {
            m3u8Url
        } else {
            ResportzHtmlParser.resolveUrl(baseUrl, m3u8Url)
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
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(GatewayConfig.UPSTREAM_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(GatewayConfig.UPSTREAM_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(GatewayConfig.UPSTREAM_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(GatewayConfig.UPSTREAM_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
    }
}
