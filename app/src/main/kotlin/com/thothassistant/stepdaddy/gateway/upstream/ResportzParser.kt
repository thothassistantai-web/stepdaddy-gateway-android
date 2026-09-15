package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val maxEmbedDepth: Int = DEFAULT_MAX_EMBED_DEPTH,
    private val mirrorLatencyTracker: MirrorLatencyTracker? = null,
) {
    private val dlhdPathFailureCounts = ConcurrentHashMap<String, Int>()
    private val dlhdPathCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val dlhdHostFailureCounts = ConcurrentHashMap<String, Int>()
    private val dlhdHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val resportzHostFailureCounts = ConcurrentHashMap<String, Int>()
    private val resportzHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val winningEmbedByChannel = ConcurrentHashMap<String, CachedWinningEmbed>()

    suspend fun fetchManifest(
        channelId: String,
        refererBase: String,
        embedUrl: String? = null,
    ): UpstreamManifest {
        val referer = "${refererBase.trimEnd('/')}/"
        tryCachedWinningEmbed(channelId)?.let { return it }

        val candidates = watchUrlCandidates(channelId, refererBase, embedUrl)
        val liveCandidates = candidates.filter { DlhdEmbedUrl.isLiveStreamPageUrl(it) }
        val embedCandidates = candidates.filter { DlhdEmbedUrl.isEmbedPageUrl(it) }
        val dlhdCandidates = candidates.filter { isDlhdRelayUrl(it) && !DlhdEmbedUrl.isLiveStreamPageUrl(it) }
        val otherCandidates =
            candidates.filterNot {
                it in liveCandidates || it in embedCandidates || it in dlhdCandidates
            }

        var lastError: Exception? = null
        // Modern /live/stream={id} pages first (2026 DaddyLive hubs + _econfig).
        for (watchUrl in liveCandidates + embedCandidates) {
            try {
                val manifest = fetchManifestFromWatchPage(
                    channelId,
                    watchUrl,
                    refererForWatchUrl(watchUrl, referer),
                )
                markWatchHostSuccess(watchUrl)
                return manifest
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                lastError = exc
                markWatchHostFailure(watchUrl)
                Log.d(TAG, "watch failed $watchUrl: ${exc.message}")
            }
        }

        if (dlhdCandidates.isNotEmpty()) {
            val raced = raceDlhdWatchUrls(channelId, dlhdCandidates, referer)
            if (raced != null) {
                return raced
            }
        }

        for (watchUrl in otherCandidates) {
            try {
                val manifest = fetchManifestFromWatchPage(channelId, watchUrl, refererForWatchUrl(watchUrl, referer))
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

    private suspend fun tryCachedWinningEmbed(channelId: String): UpstreamManifest? {
        val cached = winningEmbedByChannel[channelId] ?: return null
        val now = System.currentTimeMillis()
        if (now - cached.savedAtMs > GatewayConfig.WINNING_EMBED_CACHE_TTL_MS) {
            winningEmbedByChannel.remove(channelId, cached)
            return null
        }
        return runCatching {
            Log.d(TAG, "resportz winning-embed cache hit channel=$channelId url=${cached.embedUrl}")
            resolveFromEmbedPage(
                channelId = channelId,
                embedUrl = cached.embedUrl,
                referer = cached.referer.ifBlank { cached.embedUrl },
                iframePattern = "winning_embed_cache",
                depth = 0,
                attemptDeadlineMs = now + GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS,
            )
        }.onFailure { exc ->
            if (exc is CancellationException) throw exc
            winningEmbedByChannel.remove(channelId, cached)
            Log.d(TAG, "winning-embed cache miss channel=$channelId: ${exc.message}")
        }.getOrNull()
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
                val manifest = fetchManifestFromWatchPage(
                    channelId,
                    watchUrl,
                    refererForWatchUrl(watchUrl, referer),
                )
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
                        val manifest = fetchManifestFromWatchPage(
                            channelId,
                            watchUrl,
                            refererForWatchUrl(watchUrl, referer),
                        )
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

    private fun watchUrlCandidates(
        channelId: String,
        refererBase: String,
        embedUrl: String?,
    ): List<String> {
        val ordered = linkedSetOf<String>()
        // Prefer modern /live/stream={id} on the active mirror, then catalog embed URL.
        ordered += DlhdEmbedUrl.modernWatchUrlsForMirror(refererBase, channelId)
        embedUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered += it }
        ordered += DlhdEmbedUrl.buildRelayWatchUrls(channelId, orderedDlhdRelayHosts())
        for (host in orderedResportzHosts()) {
            val base = host.trimEnd('/')
            ordered += base + GatewayConfig.RESPORTZ_STREAM_PATH.format(channelId)
        }
        return ordered.toList()
    }

    private fun refererForWatchUrl(watchUrl: String, defaultReferer: String): String {
        if (DlhdEmbedUrl.isEmbedPageUrl(watchUrl) || DlhdEmbedUrl.isLiveStreamPageUrl(watchUrl)) {
            return watchUrl
        }
        return defaultReferer
    }

    private fun isDlhdRelayUrl(url: String): Boolean {
        if (DlhdEmbedUrl.isEmbedPageUrl(url) || DlhdEmbedUrl.isLiveStreamPageUrl(url)) return false
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
        val attemptDeadlineMs =
            System.currentTimeMillis() + GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS
        Log.d(TAG, "resportz watch $watchUrl")
        val watchHtml = getHtmlText(watchUrl, referer)
        Log.d(TAG, "resportz watch ok (${watchHtml.length} bytes)")

        // Direct m3u8 / _econfig on the watch page itself (modern hubs).
        ResportzHtmlParser.extractM3u8Url(watchHtml)?.let { match ->
            Log.d(TAG, "resportz watch m3u8 pattern=${match.pattern} url=${match.value}")
            val resolvedM3u8 = resolveM3u8Url(match.value, watchUrl)
            val (resolvedUrl, m3u8Text) = fetchM3u8TextReserved(resolvedM3u8, watchUrl)
            val manifest =
                UpstreamManifest(
                    playlistText = m3u8Text,
                    masterUrl = resolvedUrl,
                    refererHost = watchUrl,
                )
            rememberWinningEmbed(channelId, watchUrl, referer)
            return manifest
        }

        val hubUrls = ResportzHtmlParser.extractPlayerHubUrls(watchHtml, channelId, watchUrl)
        val iframeCandidates = ResportzHtmlParser.extractIframeCandidates(watchHtml, watchUrl)
        val orderedHubs =
            ResportzHtmlParser.prioritizeHubUrls(
                linkedSetOf<String>().apply {
                    hubUrls.forEach { add(it) }
                    iframeCandidates.forEach { add(it.value) }
                },
            )

        if (orderedHubs.isEmpty()) {
            val rawIframe = ResportzHtmlParser.firstRawIframeSrc(watchHtml, watchUrl)
            if (rawIframe != null && ResportzHtmlParser.isEmbedStub(rawIframe)) {
                error("embed stub host for channel $channelId ($rawIframe)")
            }
            error("Failed to find player hub/iframe for channel $channelId")
        }
        var lastError: Exception? = null
        for (hubUrl in orderedHubs) {
            val remaining = attemptDeadlineMs - System.currentTimeMillis()
            if (remaining <= 400L) {
                break
            }
            if (
                ResportzHtmlParser.isDeprioritizedHub(hubUrl) &&
                remaining < GatewayConfig.DEPRIORITIZED_HUB_MIN_REMAINING_MS
            ) {
                Log.d(
                    TAG,
                    "skip deprioritized hub remaining=${remaining}ms url=$hubUrl",
                )
                continue
            }
            val pattern =
                iframeCandidates.firstOrNull { it.value == hubUrl }?.pattern
                    ?: if (hubUrl.contains("nontongo", ignoreCase = true)) {
                        "nontongo_hub"
                    } else {
                        "player_hub"
                    }
            Log.d(TAG, "resportz hub pattern=$pattern url=$hubUrl")
            try {
                return resolveFromEmbedPage(
                    channelId = channelId,
                    embedUrl = hubUrl,
                    referer = watchUrl,
                    iframePattern = pattern,
                    depth = 0,
                    attemptDeadlineMs = attemptDeadlineMs,
                )
            } catch (exc: Exception) {
                if (exc is CancellationException && exc !is TimeoutCancellationException) {
                    throw exc
                }
                lastError = exc
                Log.d(TAG, "hub failed pattern=$pattern: ${exc.message}")
                if (isFailFast403(exc, hubUrl)) {
                    Log.d(TAG, "fail-fast 403 hub url=$hubUrl")
                }
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
        attemptDeadlineMs: Long,
    ): UpstreamManifest {
        if (ResportzHtmlParser.isEmbedStub(embedUrl)) {
            error("embed stub host for channel $channelId ($embedUrl)")
        }
        val sourcePageHtml = getHtmlText(embedUrl, referer)
        Log.d(TAG, "resportz embed ok pattern=$iframePattern (${sourcePageHtml.length} bytes)")
        val m3u8Match = ResportzHtmlParser.extractM3u8Url(sourcePageHtml)
        if (m3u8Match != null) {
            Log.d(TAG, "resportz m3u8 pattern=${m3u8Match.pattern} url=${m3u8Match.value}")
            val resolvedM3u8 = resolveM3u8Url(m3u8Match.value, embedUrl)
            val (resolvedUrl, m3u8Text) = fetchM3u8TextReserved(resolvedM3u8, embedUrl)
            Log.d(TAG, "resportz m3u8 ok (${m3u8Text.length} bytes)")
            val manifest =
                UpstreamManifest(
                    playlistText = m3u8Text,
                    masterUrl = resolvedUrl,
                    // Keep the full embed URL so referer-sensitive hosts (xameleon) pass validation.
                    refererHost = embedUrl,
                )
            rememberWinningEmbed(channelId, embedUrl, referer)
            return manifest
        }
        if (depth + 1 >= maxEmbedDepth) {
            error("Failed to find encoded m3u8 source for channel $channelId")
        }
        val nestedHubs =
            ResportzHtmlParser.prioritizeHubUrls(
                linkedSetOf<String>().apply {
                    ResportzHtmlParser.extractPlayerHubUrls(sourcePageHtml, channelId, embedUrl)
                        .forEach { add(it) }
                    ResportzHtmlParser.extractIframeCandidates(sourcePageHtml, embedUrl)
                        .forEach { add(it.value) }
                },
            )
        if (nestedHubs.isEmpty()) {
            error("Failed to find encoded m3u8 source for channel $channelId")
        }
        var nestedError: Exception? = null
        for (childUrl in nestedHubs) {
            val remaining = attemptDeadlineMs - System.currentTimeMillis()
            if (remaining <= 400L) {
                break
            }
            if (
                ResportzHtmlParser.isDeprioritizedHub(childUrl) &&
                remaining < GatewayConfig.DEPRIORITIZED_HUB_MIN_REMAINING_MS
            ) {
                Log.d(TAG, "skip nested deprioritized hub remaining=${remaining}ms url=$childUrl")
                continue
            }
            Log.d(TAG, "resportz nested hub depth=${depth + 1} url=$childUrl")
            try {
                return resolveFromEmbedPage(
                    channelId = channelId,
                    embedUrl = childUrl,
                    referer = embedUrl,
                    iframePattern = "nested_hub",
                    depth = depth + 1,
                    attemptDeadlineMs = attemptDeadlineMs,
                )
            } catch (exc: Exception) {
                if (exc is CancellationException && exc !is TimeoutCancellationException) {
                    throw exc
                }
                nestedError = exc
                if (isFailFast403(exc, childUrl)) {
                    Log.d(TAG, "fail-fast 403 nested hub url=$childUrl")
                }
            }
        }
        throw nestedError ?: error("Failed to find encoded m3u8 source for channel $channelId")
    }

    private fun rememberWinningEmbed(channelId: String, embedUrl: String, referer: String) {
        val trimmed = channelId.trim()
        if (trimmed.isEmpty() || embedUrl.isBlank()) return
        winningEmbedByChannel[trimmed] =
            CachedWinningEmbed(
                embedUrl = embedUrl,
                referer = referer,
                savedAtMs = System.currentTimeMillis(),
            )
    }

    private fun isFailFast403(exc: Exception, hubUrl: String): Boolean {
        if (!ResportzHtmlParser.isFailFast403Hub(hubUrl)) return false
        val status = exc as? HttpStatusException
        if (status != null) return status.code == 403
        return exc.message?.contains("HTTP 403") == true
    }

    private fun resolveM3u8Url(m3u8Url: String, baseUrl: String): String {
        val cleaned = EconfigDecoder.normalizeStreamUrl(m3u8Url)
        return if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            cleaned
        } else {
            ResportzHtmlParser.resolveUrl(baseUrl, cleaned)
        }
    }

    private suspend fun fetchM3u8TextReserved(m3u8Url: String, referer: String): Pair<String, String> {
        // Hub walk may have burned the outer mirror withTimeout; keep m3u8 fetch alive.
        return withContext(NonCancellable) {
            withTimeout(GatewayConfig.M3U8_FETCH_TIMEOUT_MS) {
                fetchM3u8Text(m3u8Url, referer)
            }
        }
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

    private suspend fun getHtmlText(url: String, referer: String): String =
        withTimeout(GatewayConfig.HUB_PAGE_TIMEOUT_MS) {
            getText(url, referer)
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

    private data class CachedWinningEmbed(
        val embedUrl: String,
        val referer: String,
        val savedAtMs: Long,
    )

    companion object {
        private const val TAG = "ResportzParser"

        /** Enough depth for nontongo → dlive → assetrage `_econfig` (and similar chains). */
        const val DEFAULT_MAX_EMBED_DEPTH: Int = 8

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
