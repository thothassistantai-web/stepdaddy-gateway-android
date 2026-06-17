package com.nova.stepdaddylivehd.gateway.upstream

import android.content.Context
import android.util.Log
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.epg.EpgChannelMapper
import com.nova.stepdaddylivehd.gateway.model.Channel
import com.nova.stepdaddylivehd.gateway.model.UpstreamChannelRow
import com.nova.stepdaddylivehd.gateway.model.UpstreamManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import kotlin.math.min
import java.util.concurrent.TimeUnit

class DaddyLiveClient(
    private val environment: GatewayEnvironment,
    private val epgChannelMapper: EpgChannelMapper? = null,
    private val logoResolver: LogoResolver? = null,
    private val channelMetaStore: ChannelMetaStore? = null,
    private val resportzParser: ResportzParser = ResportzParser(),
    private val client: OkHttpClient = ResportzParser.defaultClient(),
    context: Context,
) {
    private val prefs = context.getSharedPreferences("stepdaddy_channels", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val cacheMutex = Mutex()
    private val healingLock = Any()
    private val upstreamFetchSem = Semaphore(GatewayConfig.UPSTREAM_FETCH_MAX_CONCURRENT)
    @Volatile
    private var refreshInFlight = false
    private val streamCache = mutableMapOf<String, CachedManifest>()
    private val staleStreamCache = mutableMapOf<String, CachedManifest>()
    private val upstreamCache = mutableMapOf<String, CachedUpstream>()
    private val deadMirrors = mutableMapOf<String, Long>()
    private val mirrorFailureCounts = mutableMapOf<String, Int>()
    private val mirrorRetryAfterMs = mutableMapOf<String, Long>()
    private val streamFailures = mutableMapOf<String, Int>()
    private val invalidateCooldownUntilMs = mutableMapOf<String, Long>()
    private val healingLog = ArrayDeque<String>(GatewayConfig.HEALING_LOG_MAX)
    @Volatile
    private var lastHealingAction: String = "none"
    @Volatile
    private var outageModeUntilMs: Long = 0L
    @Volatile
    private var lastServedFromStaleCache: Boolean = false

    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    @Volatile
    var activeBaseUrl: String = environment.dlhdBaseUrl
        private set

    private var lastChannelRefreshMs: Long = 0L

    init {
        loadDiskCache()
    }

    fun scheduleChannelRefresh(force: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (refreshInFlight && !force) {
            return
        }
        refreshScope.launch {
            refreshInFlight = true
            try {
                runCatching { ensureChannels(force = force) }
                    .onSuccess {
                        onComplete?.invoke()
                        schedulePrewarmDelayed()
                    }
                    .onFailure { exc -> Log.w(TAG, "Channel refresh failed", exc) }
            } finally {
                refreshInFlight = false
            }
        }
    }

    fun schedulePrewarmDelayed() {
        refreshScope.launch {
            delay(5_000L)
            schedulePrewarm()
        }
    }

    fun schedulePrewarm() {
        refreshScope.launch {
            for (channelId in GatewayConfig.PREWARM_CHANNEL_IDS) {
                runCatching {
                    resolveStream(
                        channelId,
                        useProxy = true,
                        apiUrl = environment.loopbackBase(),
                    )
                }
                    .onFailure { exc ->
                        Log.d(TAG, "Prewarm skipped for $channelId: ${exc.message}")
                    }
            }
        }
    }

    suspend fun ensureChannels(force: Boolean = false) {
        val stale = System.currentTimeMillis() - lastChannelRefreshMs > GatewayConfig.CHANNEL_REFRESH_INTERVAL_MS
        if (!force && channels.isNotEmpty() && !stale) {
            return
        }
        loadMutex.withLock {
            if (!force && channels.isNotEmpty() && !stale) {
                return
            }
            val loaded = loadChannelsFromUpstream()
            if (loaded.isNotEmpty()) {
                channels = loaded
                lastChannelRefreshMs = System.currentTimeMillis()
                saveDiskCache()
            }
        }
    }

    suspend fun resolveStream(
        channelId: String,
        useProxy: Boolean = false,
        apiUrl: String = "",
    ): String {
        val now = System.currentTimeMillis()
        val cacheKey = "$channelId:${if (useProxy) 1 else 0}:$apiUrl"
        cacheMutex.withLock {
            val cached = streamCache[cacheKey]
            if (cached != null && now - cached.savedAtMs < GatewayConfig.STREAM_CACHE_TTL_MS) {
                return cached.rewrittenPlaylist
            }
        }
        try {
            val manifest = fetchManifestWithMirrors(channelId)
            val rewritten = M3u8Rewriter.rewrite(
                manifest.playlistText,
                manifest.masterUrl,
                refererHost = manifest.refererHost,
                useProxy = useProxy,
                apiUrl = apiUrl,
            )
            cacheMutex.withLock {
                val entry = CachedManifest(System.currentTimeMillis(), rewritten)
                streamCache[cacheKey] = entry
                staleStreamCache[cacheKey] = entry
            }
            lastServedFromStaleCache = false
            return rewritten
        } catch (exc: Exception) {
            if (exc is CancellationException) {
                throw exc
            }
            cacheMutex.withLock {
                val stale = staleStreamCache[cacheKey]
                val staleTtl = if (isGlobalOutageActive()) {
                    GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
                } else {
                    GatewayConfig.STALE_STREAM_TTL_MS
                }
                if (stale != null && now - stale.savedAtMs < staleTtl) {
                    lastServedFromStaleCache = true
                    Log.w(TAG, "cache-serve mode channel=$channelId reason=${exc.message}")
                    return stale.rewrittenPlaylist
                }
            }
            throw exc
        }
    }

    private suspend fun fetchManifestWithMirrors(channelId: String): UpstreamManifest {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = upstreamCache[channelId]
            if (cached != null && now - cached.savedAtMs < GatewayConfig.UPSTREAM_CACHE_TTL_MS) {
                return cached.manifest
            }
        }
        if (!upstreamFetchSem.tryAcquire()) {
            cacheMutex.withLock {
                val stale = upstreamCache[channelId]
                val staleTtl = if (isGlobalOutageActive()) {
                    GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
                } else {
                    GatewayConfig.UPSTREAM_STALE_TTL_MS
                }
                if (stale != null && now - stale.savedAtMs < staleTtl) {
                    lastServedFromStaleCache = true
                    Log.w(TAG, "Serving stale upstream for $channelId (fetch slots full)")
                    return stale.manifest
                }
            }
            throw IllegalStateException("upstream_busy")
        }
        try {
            return fetchManifestWithMirrorsInner(channelId, now)
        } finally {
            upstreamFetchSem.release()
        }
    }

    private suspend fun fetchManifestWithMirrorsInner(
        channelId: String,
        startedAtMs: Long,
    ): UpstreamManifest {
        var lastError: Exception? = null
        val resportzTimedOut = mutableSetOf<String>()
        var attemptedMirrors = 0
        var connectivityFailures = 0
        for (baseUrl in orderedMirrorUrls()) {
            if (isMirrorDead(baseUrl) || isMirrorCoolingDown(baseUrl)) continue
            attemptedMirrors++
            val mirrorKey = baseUrl.trimEnd('/')
            if (isDaddyLiveMirror(baseUrl) && mirrorKey in resportzTimedOut) {
                continue
            }
            val elapsed = System.currentTimeMillis() - startedAtMs
            val remaining = GatewayConfig.STREAM_FETCH_TIMEOUT_MS - elapsed
            if (remaining <= 500L) {
                break
            }
            val attemptBudget = minOf(GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS, remaining)
            try {
                val manifest = withTimeout(attemptBudget) {
                    if (isDaddyLiveMirror(baseUrl)) {
                        resportzParser.fetchManifest(channelId, baseUrl)
                    } else {
                        error("Non-DaddyLive mirrors not implemented in MVP: $baseUrl")
                    }
                }
                markMirrorAlive(baseUrl)
                activeBaseUrl = baseUrl
                clearGlobalOutageIfOpen()
                cacheMutex.withLock {
                    upstreamCache[channelId] = CachedUpstream(System.currentTimeMillis(), manifest)
                }
                return manifest
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: TimeoutCancellationException) {
                lastError = exc
                if (isDaddyLiveMirror(baseUrl)) {
                    resportzTimedOut += mirrorKey
                }
                connectivityFailures++
                markMirrorFailure(baseUrl)
                Log.d(TAG, "Mirror timeout for $channelId on $baseUrl (${attemptBudget}ms)")
            } catch (exc: Exception) {
                lastError = exc
                if (isChannelSpecificError(exc)) {
                    Log.d(TAG, "Channel-specific failure for $channelId: ${exc.message}")
                    break
                }
                if (isConnectivityFailure(exc) || shouldMarkMirrorDead(exc)) {
                    connectivityFailures++
                    markMirrorFailure(baseUrl)
                    Log.d(TAG, "Mirror failed for $channelId on $baseUrl: ${exc.message}")
                    continue
                }
                Log.d(TAG, "Non-mirror failure for $channelId on $baseUrl: ${exc.message}")
            }
        }
        if (attemptedMirrors > 0 && connectivityFailures >= attemptedMirrors) {
            openGlobalOutage("connectivity_failures=$connectivityFailures mirrors=$attemptedMirrors")
        }
        cacheMutex.withLock {
            val stale = upstreamCache[channelId]
            val staleTtl = if (isGlobalOutageActive()) {
                GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
            } else {
                GatewayConfig.UPSTREAM_STALE_TTL_MS
            }
            if (stale != null && System.currentTimeMillis() - stale.savedAtMs < staleTtl) {
                lastServedFromStaleCache = true
                Log.w(TAG, "cache-serve upstream channel=$channelId after mirror failures")
                return stale.manifest
            }
        }
        throw lastError ?: IllegalStateException(
            if (isGlobalOutageActive()) "upstream_outage" else "No mirrors available for channel $channelId",
        )
    }

    private suspend fun loadChannelsFromUpstream(): List<Channel> {
        var lastError: Exception? = null
        for (baseUrl in orderedMirrorUrls()) {
            if (isMirrorDead(baseUrl)) continue
            try {
                val rows = fetchChannelRows(baseUrl)
                if (rows.isEmpty()) continue
                markMirrorAlive(baseUrl)
                activeBaseUrl = baseUrl
                return rows.map { row ->
                    channelFromRow(row.channelId, row.channelName)
                }.sortedWith(GroupTitleResolver.channelComparator())
            } catch (exc: Exception) {
                lastError = exc
                Log.d(TAG, "Channel list failed on $baseUrl: ${exc.message}")
            }
        }
        if (channels.isNotEmpty()) {
            return channels
        }
        throw lastError ?: IllegalStateException("Failed to load channels")
    }

    private suspend fun fetchChannelRows(baseUrl: String): List<UpstreamChannelRow> =
        withContext(Dispatchers.IO) {
            if (!isDaddyLiveMirror(baseUrl)) {
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/channels")
                .header("User-Agent", GatewayConfig.USER_AGENT)
                .header("Referer", "${baseUrl.trimEnd('/')}/")
                .get()
                .build()
            val body = client.getText(request)
            json.decodeFromString<List<UpstreamChannelRow>>(body)
        }

    private fun orderedMirrorUrls(): List<String> {
        val ordered = linkedSetOf<String>()
        ordered += environment.dlhdBaseUrl.trimEnd('/')
        ordered += environment.mirrorUrls.map { it.trimEnd('/') }
        return ordered.toList()
    }

    private fun isDaddyLiveMirror(baseUrl: String): Boolean {
        val host = URL(baseUrl.trimEnd('/')).host.lowercase()
        return GatewayConfig.DADDYLIVE_HOSTS.any { host.contains(it) }
    }

    fun noteStreamSuccess(channelId: String) {
        lastServedFromStaleCache = false
        synchronized(healingLock) {
            streamFailures.remove(channelId)
            invalidateCooldownUntilMs.remove(channelId)
        }
    }

    fun noteStreamFailure(channelId: String, exc: Exception) {
        if (isTransientError(exc)) {
            return
        }
        if (!isChannelSpecificStreamFailure(exc)) {
            Log.d(TAG, "Skipping cache invalidate for global/mirror failure channel=$channelId: ${exc.message}")
            return
        }
        val now = System.currentTimeMillis()
        val inCooldown = synchronized(healingLock) {
            now < (invalidateCooldownUntilMs[channelId] ?: 0L)
        }
        if (inCooldown) {
            Log.d(TAG, "Skipping invalidate during cooldown channel=$channelId")
            return
        }
        val count = synchronized(healingLock) {
            val next = (streamFailures[channelId] ?: 0) + 1
            streamFailures[channelId] = next
            next
        }
        Log.d(TAG, "Stream failure channel=$channelId count=$count: ${exc.message}")
        if (count >= GatewayConfig.STREAM_FAILURE_INVALIDATE_THRESHOLD) {
            refreshScope.launch {
                invalidateChannelCaches(channelId, exc)
                recordHealingAction("invalidate_channel $channelId failures=$count")
            }
            synchronized(healingLock) {
                invalidateCooldownUntilMs[channelId] = now + GatewayConfig.INVALIDATE_COOLDOWN_MS
            }
        }
    }

    fun recordHealingAction(action: String) {
        synchronized(healingLock) {
            recordHealingActionLocked(action)
        }
    }

    private fun recordHealingActionLocked(action: String) {
        val entry = "${System.currentTimeMillis()}:$action"
        lastHealingAction = action
        healingLog.addLast(entry)
        while (healingLog.size > GatewayConfig.HEALING_LOG_MAX) {
            healingLog.removeFirst()
        }
        Log.i(TAG, "Healing: $action")
    }

    fun healingSnapshot(): HealingSnapshot = synchronized(healingLock) {
        HealingSnapshot(
            lastAction = lastHealingAction,
            recentActions = healingLog.toList(),
            streamFailureCount = streamFailures.size,
            deadMirrorCount = deadMirrors.size,
            streamCacheSize = streamCache.size,
            upstreamCacheSize = upstreamCache.size,
            outageMode = isGlobalOutageActive(),
            cacheServeMode = lastServedFromStaleCache,
            breakerOpen = isGlobalOutageActive(),
            breakerRemainingMs = outageRemainingMs(),
        )
    }

    suspend fun invalidateChannelCaches(channelId: String, reason: Exception? = null) {
        cacheMutex.withLock {
            invalidateChannelCachesLocked(channelId, reason)
        }
    }

    private fun invalidateChannelCachesLocked(channelId: String, reason: Exception? = null) {
        streamCache.keys.filter { it.startsWith("$channelId:") }.forEach { streamCache.remove(it) }
        if (shouldPurgeUpstreamCache(reason)) {
            staleStreamCache.keys.filter { it.startsWith("$channelId:") }.forEach { staleStreamCache.remove(it) }
            upstreamCache.remove(channelId)
        } else {
            Log.d(TAG, "Keeping stale upstream cache for $channelId (${reason?.message})")
        }
        synchronized(healingLock) {
            streamFailures.remove(channelId)
        }
    }

    /** Drop expired fresh entries only; keep upstream entries within stale TTL for unrelated channels. */
    suspend fun invalidateStaleCaches() {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            val staleTtl = if (isGlobalOutageActive()) {
                GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
            } else {
                GatewayConfig.STALE_STREAM_TTL_MS
            }
            val upstreamStaleTtl = if (isGlobalOutageActive()) {
                GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
            } else {
                GatewayConfig.UPSTREAM_STALE_TTL_MS
            }
            streamCache.entries.removeIf { now - it.value.savedAtMs > GatewayConfig.STREAM_CACHE_TTL_MS }
            staleStreamCache.entries.removeIf { now - it.value.savedAtMs > staleTtl }
            upstreamCache.entries.removeIf { now - it.value.savedAtMs > upstreamStaleTtl }
        }
        recordHealingAction("purge_stale_caches")
    }

    /** Content-proxy healing: refresh rewritten playlists without evicting upstream manifests. */
    suspend fun invalidateFreshStreamCaches() {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            streamCache.entries.removeIf { now - it.value.savedAtMs > GatewayConfig.STREAM_CACHE_TTL_MS }
        }
        recordHealingAction("purge_fresh_stream_caches")
    }

    suspend fun probeMirrors(): Boolean {
        if (isGlobalOutageActive()) {
            Log.i(TAG, "upstream-outage mode active; probe throttled")
        }
        for (baseUrl in orderedMirrorUrls()) {
            if (isMirrorDead(baseUrl) || isMirrorCoolingDown(baseUrl)) continue
            try {
                val rows = fetchChannelRows(baseUrl)
                if (rows.isNotEmpty()) {
                    markMirrorAlive(baseUrl)
                    clearGlobalOutageIfOpen()
                    return true
                }
            } catch (exc: Exception) {
                markMirrorFailure(baseUrl)
                Log.d(TAG, "Mirror probe failed on $baseUrl: ${exc.message}")
            }
        }
        return false
    }

    private fun isTransientError(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message == "upstream_busy") return true
        if (message.contains("timeout", ignoreCase = true)) return true
        if (message.contains("timed out", ignoreCase = true)) return true
        return false
    }

    private fun isCdnFetchError(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        return message.startsWith("HTTP 4") || message.startsWith("HTTP 5")
    }

    private fun isConnectivityFailure(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message.contains("failed to connect", ignoreCase = true)) return true
        if (message.contains("unable to resolve host", ignoreCase = true)) return true
        if (message.contains("connection reset", ignoreCase = true)) return true
        if (message.contains("network is unreachable", ignoreCase = true)) return true
        if (message.contains("timeout", ignoreCase = true)) return true
        return false
    }

    /** Only resportz watch / mirror API faults should poison the shared mirror pool. */
    private fun shouldMarkMirrorDead(exc: Exception): Boolean {
        if (isCdnFetchError(exc)) return false
        val message = exc.message.orEmpty()
        if (message.contains("failed to connect", ignoreCase = true)) return false
        if (message.contains("unable to resolve host", ignoreCase = true)) return false
        if (message.contains("connection reset", ignoreCase = true)) return false
        if (message.contains("resportz watch", ignoreCase = true)) return true
        if (message.startsWith("HTTP ") && message.contains("resportz")) return true
        return false
    }

    /** Resportz scrape misses are per-channel; do not poison the shared mirror pool. */
    private fun isChannelSpecificError(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message.contains("encoded m3u8", ignoreCase = true)) return true
        if (message.contains("iframe source", ignoreCase = true)) return true
        if (message.contains("embed stub host", ignoreCase = true)) return true
        if (message.contains("empty iframe", ignoreCase = true)) return true
        if (message.contains("empty encoded source", ignoreCase = true)) return true
        return false
    }

    /** Only count failures toward per-channel cache purge when the channel's own upstream/CDN broke. */
    private fun isChannelSpecificStreamFailure(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message.contains("No mirrors available", ignoreCase = true)) return false
        if (message.contains("upstream_busy")) return false
        if (message.contains("upstream_outage", ignoreCase = true)) return false
        if (message.contains("upstream_timeout", ignoreCase = true)) return false
        if (message.contains("failed to connect", ignoreCase = true)) return false
        if (message.contains("unable to resolve host", ignoreCase = true)) return false
        if (message.contains("timeout", ignoreCase = true)) return false
        if (message.contains("timed out", ignoreCase = true)) return false
        if (message.contains("stream_not_found", ignoreCase = true)) return false
        if (isChannelSpecificError(exc)) return true
        if (message.contains("HTTP 403") || message.contains("HTTP 502") ||
            message.contains("HTTP 504") || message.contains("HTTP 500")
        ) {
            return true
        }
        return true
    }

    private fun shouldPurgeUpstreamCache(reason: Exception?): Boolean {
        if (reason == null) return true
        return isChannelSpecificStreamFailure(reason)
    }

    private fun isMirrorDead(baseUrl: String): Boolean {
        val key = baseUrl.trimEnd('/')
        val failedAt = deadMirrors[key] ?: return false
        if (System.currentTimeMillis() - failedAt > GatewayConfig.DEAD_MIRROR_TTL_MS) {
            deadMirrors.remove(key)
            return false
        }
        return true
    }

    private fun markMirrorDead(baseUrl: String) {
        val key = baseUrl.trimEnd('/')
        deadMirrors[key] = System.currentTimeMillis()
        mirrorFailureCounts[key] = GatewayConfig.WATCHDOG_RESTART_THRESHOLD
        mirrorRetryAfterMs[key] = System.currentTimeMillis() + GatewayConfig.MIRROR_FAILURE_BACKOFF_MAX_MS
    }

    private fun markMirrorAlive(baseUrl: String) {
        val key = baseUrl.trimEnd('/')
        deadMirrors.remove(key)
        mirrorFailureCounts.remove(key)
        mirrorRetryAfterMs.remove(key)
    }

    private fun markMirrorFailure(baseUrl: String) {
        val key = baseUrl.trimEnd('/')
        val now = System.currentTimeMillis()
        val nextCount = (mirrorFailureCounts[key] ?: 0) + 1
        mirrorFailureCounts[key] = nextCount
        val backoff = min(
            GatewayConfig.MIRROR_FAILURE_BACKOFF_BASE_MS * (1L shl min(nextCount - 1, 4)),
            GatewayConfig.MIRROR_FAILURE_BACKOFF_MAX_MS,
        )
        mirrorRetryAfterMs[key] = now + backoff
        if (backoff >= GatewayConfig.DEAD_MIRROR_TTL_MS) {
            deadMirrors[key] = now
        }
        Log.i(TAG, "breaker-open mirror=$key backoffMs=$backoff failures=$nextCount")
    }

    private fun isMirrorCoolingDown(baseUrl: String): Boolean {
        val key = baseUrl.trimEnd('/')
        val retryAt = mirrorRetryAfterMs[key] ?: return false
        return System.currentTimeMillis() < retryAt
    }

    private fun openGlobalOutage(reason: String) {
        outageModeUntilMs = System.currentTimeMillis() + GatewayConfig.GLOBAL_OUTAGE_BREAKER_MS
        recordHealingAction("upstream_outage_open $reason")
    }

    private fun clearGlobalOutageIfOpen() {
        if (outageModeUntilMs == 0L) return
        outageModeUntilMs = 0L
        recordHealingAction("upstream_outage_closed")
    }

    fun isGlobalOutageActive(): Boolean = System.currentTimeMillis() < outageModeUntilMs

    fun outageRemainingMs(): Long = (outageModeUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    fun shouldSuppressRestartForOutage(): Boolean = isGlobalOutageActive() || lastServedFromStaleCache

    fun reportHealthyStart() {
        lastServedFromStaleCache = false
        clearGlobalOutageIfOpen()
    }

    private fun loadDiskCache() {
        val raw = prefs.getString("channels_json", null) ?: return
        try {
            val root = JSONObject(raw)
            val rows = root.getJSONArray("channels")
            val parsed = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val id = row.optString("id")
                    val name = row.optString("name")
                    val tvgId = row.optString("tvg_id").takeIf { it.isNotBlank() }
                        ?: epgChannelMapper?.tvgIdFor(id, name)
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(channelFromRow(id, name, tvgId))
                    }
                }
            }
            if (parsed.isNotEmpty()) {
                channels = parsed
            }
            activeBaseUrl = root.optString("base_url", environment.dlhdBaseUrl)
            lastChannelRefreshMs = root.optLong("saved_at", 0L)
        } catch (_: Exception) {
            // Ignore corrupt cache.
        }
    }

    private fun channelFromRow(channelId: String, channelName: String, cachedTvgId: String? = null): Channel {
        val id = channelId.trim()
        val name = channelName.trim().replace("#", "")
        val tags = channelMetaStore?.tagsFor(name).orEmpty()
        val tvgId = cachedTvgId?.takeIf { it.isNotBlank() }
            ?: epgChannelMapper?.tvgIdFor(id, name)
        val metaLogo = channelMetaStore?.logoFor(name)
        return Channel(
            id = id,
            name = name,
            tags = tags,
            tvgId = tvgId,
            logo = logoResolver?.resolveLogoUrlBlocking(
                environment.loopbackBase(),
                name,
                tvgId,
                metaLogo,
            ),
        )
    }

    private fun saveDiskCache() {
        if (channels.isEmpty()) return
        val channelsArray = org.json.JSONArray()
        channels.forEach { channel ->
            channelsArray.put(
                JSONObject()
                    .put("id", channel.id)
                    .put("name", channel.name)
                    .put("tvg_id", channel.tvgId),
            )
        }
        val payload = JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("base_url", activeBaseUrl)
            .put("channels", channelsArray)
        prefs.edit().putString("channels_json", payload.toString()).apply()
    }

    private data class CachedManifest(
        val savedAtMs: Long,
        val rewrittenPlaylist: String,
    )

    private data class CachedUpstream(
        val savedAtMs: Long,
        val manifest: UpstreamManifest,
    )

    data class HealingSnapshot(
        val lastAction: String,
        val recentActions: List<String>,
        val streamFailureCount: Int,
        val deadMirrorCount: Int,
        val streamCacheSize: Int,
        val upstreamCacheSize: Int,
        val outageMode: Boolean,
        val cacheServeMode: Boolean,
        val breakerOpen: Boolean,
        val breakerRemainingMs: Long,
    )

    companion object {
        private const val TAG = "DaddyLiveClient"
    }
}
