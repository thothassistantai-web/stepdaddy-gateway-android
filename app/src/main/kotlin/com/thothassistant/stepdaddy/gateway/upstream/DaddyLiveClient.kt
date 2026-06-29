package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.TvgIdResolver
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.UpstreamChannelRow
import com.thothassistant.stepdaddy.gateway.model.UpstreamManifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private val tvgIdResolver: TvgIdResolver? = null,
    private val logoResolver: LogoResolver? = null,
    private val channelMetaStore: ChannelMetaStore? = null,
    resportzParser: ResportzParser? = null,
    private val client: OkHttpClient = ResportzParser.defaultClient(),
    context: Context,
) {
    private val prefs = context.getSharedPreferences("stepdaddy_channels", Context.MODE_PRIVATE)
    private val staleGoodCacheStore = StaleGoodCacheStore(context)
    private val channelNameOverrides = ChannelNameOverrides(context)
    private val mirrorLatencyTracker = MirrorLatencyTracker()
    private val resportzParser: ResportzParser =
        resportzParser ?: ResportzParser(mirrorLatencyTracker = mirrorLatencyTracker)
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
    private var consecutiveOutageOpens: Int = 0
    @Volatile
    private var lastServedFromStaleCache: Boolean = false
    @Volatile
    private var lastUpstreamSuccessMs: Long = 0L
    @Volatile
    private var lastCanarySnapshot: CanarySnapshot = CanarySnapshot()
    @Volatile
    private var streamCacheHits: Long = 0L
    @Volatile
    private var streamCacheMisses: Long = 0L

    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    @Volatile
    private var channelRevision: Int = 0

    @Volatile
    var activeBaseUrl: String = environment.dlhdBaseUrl
        private set

    private var lastChannelRefreshMs: Long = 0L
    private val initialLoadGate = CompletableDeferred<Unit>()

    fun channelRevision(): Int = channelRevision

    suspend fun awaitInitialLoad() {
        initialLoadGate.await()
    }

    init {
        // Fast path: serve disk-cached channels before HTTP listen (async load blocked boot for seconds).
        loadDiskCache()
        initialLoadGate.complete(Unit)
        refreshScope.launch {
            staleGoodCacheStore.purgeExpired(GatewayConfig.STALE_DISK_TTL_MS)
            runCatching {
                logoResolver?.awaitLoaded(120_000L)
                enrichCachedChannelsQuietly()
            }.onFailure { exc ->
                Log.d(TAG, "Deferred logo enrich skipped: ${exc.message}")
            }
        }
    }

    suspend fun reEnrichLogos(): CatalogLogoEnricher.Result {
        loadMutex.withLock {
            val enricher = catalogLogoEnricher() ?: return CatalogLogoEnricher.Result(0, 0, 0)
            val (enriched, result) = enricher.enrichChannels(channels)
            if (result.assigned > 0) {
                channels = enriched
                channelRevision++
                saveDiskCache()
            }
            return result
        }
    }

    private fun enrichCachedChannelsQuietly() {
        val enricher = catalogLogoEnricher() ?: return
        val (enriched, result) = enricher.enrichChannels(channels)
        if (result.assigned > 0) {
            channels = enriched
            channelRevision++
            saveDiskCache()
        }
    }

    private fun catalogLogoEnricher(): CatalogLogoEnricher? {
        if (logoResolver == null) return null
        return CatalogLogoEnricher(logoResolver, channelMetaStore)
    }

    fun streamFetchTimeoutMs(): Long =
        if (isGlobalOutageActive()) {
            GatewayConfig.OUTAGE_STREAM_FETCH_TIMEOUT_MS
        } else {
            GatewayConfig.STREAM_FETCH_TIMEOUT_MS
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
                val enricher = catalogLogoEnricher()
                channels = if (enricher != null) {
                    enricher.enrichChannels(loaded).first
                } else {
                    loaded
                }
                channelRevision++
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
                streamCacheHits++
                return cached.rewrittenPlaylist
            }
        }
        streamCacheMisses++
        if (isGlobalOutageActive()) {
            serveStaleStreamFromCaches(cacheKey, channelId, now)?.let { return it }
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
            val savedAt = System.currentTimeMillis()
            cacheMutex.withLock {
                val entry = CachedManifest(savedAt, rewritten)
                streamCache[cacheKey] = entry
                staleStreamCache[cacheKey] = entry
            }
            staleGoodCacheStore.saveStream(cacheKey, channelId, rewritten)
            lastUpstreamSuccessMs = savedAt
            lastServedFromStaleCache = false
            return rewritten
        } catch (exc: Exception) {
            if (exc is CancellationException) {
                throw exc
            }
            serveStaleStreamFromCaches(cacheKey, channelId, now)?.let { return it }
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
        if (isGlobalOutageActive()) {
            serveStaleUpstreamFromCaches(channelId, now)?.let { return it }
        }
        val acquiredSlot = withTimeoutOrNull(GatewayConfig.UPSTREAM_FETCH_WAIT_MS) {
            upstreamFetchSem.acquire()
            true
        } ?: false
        if (!acquiredSlot) {
            serveStaleUpstreamFromCaches(channelId, now)?.let { return it }
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
        val mirrors = orderedMirrorUrls()
        if (
            GatewayConfig.HEDGED_MIRROR_RACE_ENABLED &&
            !isGlobalOutageActive() &&
            mirrors.size >= 2
        ) {
            val hedged = tryHedgedMirrorRace(channelId, mirrors.take(2), startedAtMs)
            if (hedged != null) {
                return hedged
            }
        }
        return fetchManifestSerial(channelId, mirrors, startedAtMs)
    }

    private suspend fun tryHedgedMirrorRace(
        channelId: String,
        mirrors: List<String>,
        startedAtMs: Long,
    ): UpstreamManifest? = coroutineScope {
        val elapsed = System.currentTimeMillis() - startedAtMs
        val remaining = streamFetchTimeoutMs() - elapsed
        val raceBudget = minOf(
            GatewayConfig.HEDGED_MIRROR_RACE_TIMEOUT_MS,
            remaining,
            mirrorAttemptTimeoutMs(),
        )
        if (raceBudget <= 500L) return@coroutineScope null

        val eligible = mirrors.filter { !isMirrorDead(it) && !isMirrorCoolingDown(it) }
        if (eligible.size < 2) return@coroutineScope null

        val winner = CompletableDeferred<UpstreamManifest>()
        val jobs = eligible.take(2).map { baseUrl ->
            async {
                val mirrorKey = baseUrl.trimEnd('/')
                val attemptStarted = System.nanoTime()
                try {
                    val manifest = withTimeout(raceBudget) {
                        if (isDaddyLiveMirror(baseUrl)) {
                            resportzParser.fetchManifest(channelId, baseUrl)
                        } else {
                            error("Non-DaddyLive mirrors not implemented in MVP: $baseUrl")
                        }
                    }
                    val latencyMs = (System.nanoTime() - attemptStarted) / 1_000_000L
                    mirrorLatencyTracker.recordMirrorSuccess(baseUrl, latencyMs)
                    markMirrorAlive(baseUrl)
                    activeBaseUrl = baseUrl
                    clearGlobalOutageIfOpen()
                    val savedAt = System.currentTimeMillis()
                    cacheMutex.withLock {
                        upstreamCache[channelId] = CachedUpstream(savedAt, manifest)
                    }
                    staleGoodCacheStore.saveUpstream(channelId, manifest)
                    lastUpstreamSuccessMs = savedAt
                    if (!winner.isCompleted) {
                        winner.complete(manifest)
                    }
                    manifest
                } catch (exc: CancellationException) {
                    throw exc
                } catch (exc: Exception) {
                    mirrorLatencyTracker.recordMirrorFailure(baseUrl)
                    if (isConnectivityFailure(exc) || shouldMarkMirrorDead(exc)) {
                        markMirrorFailure(baseUrl)
                    }
                    Log.d(TAG, "Hedged mirror failed for $channelId on $mirrorKey: ${exc.message}")
                    null
                }
            }
        }
        val result = withTimeoutOrNull(raceBudget) {
            runCatching { winner.await() }.getOrNull()
        }
        jobs.forEach { it.cancel() }
        result
    }

    private suspend fun fetchManifestSerial(
        channelId: String,
        mirrors: List<String>,
        startedAtMs: Long,
    ): UpstreamManifest {
        var lastError: Exception? = null
        val resportzTimedOut = mutableSetOf<String>()
        var attemptedMirrors = 0
        var connectivityFailures = 0
        for (baseUrl in mirrors) {
            if (isMirrorDead(baseUrl) || isMirrorCoolingDown(baseUrl)) continue
            attemptedMirrors++
            val mirrorKey = baseUrl.trimEnd('/')
            if (isDaddyLiveMirror(baseUrl) && mirrorKey in resportzTimedOut) {
                continue
            }
            val elapsed = System.currentTimeMillis() - startedAtMs
            val remaining = streamFetchTimeoutMs() - elapsed
            if (remaining <= 500L) {
                break
            }
            val attemptBudget = minOf(mirrorAttemptTimeoutMs(), remaining)
            val attemptStarted = System.nanoTime()
            try {
                val manifest = withTimeout(attemptBudget) {
                    if (isDaddyLiveMirror(baseUrl)) {
                        resportzParser.fetchManifest(channelId, baseUrl)
                    } else {
                        error("Non-DaddyLive mirrors not implemented in MVP: $baseUrl")
                    }
                }
                val latencyMs = (System.nanoTime() - attemptStarted) / 1_000_000L
                mirrorLatencyTracker.recordMirrorSuccess(baseUrl, latencyMs)
                markMirrorAlive(baseUrl)
                activeBaseUrl = baseUrl
                clearGlobalOutageIfOpen()
                val savedAt = System.currentTimeMillis()
                cacheMutex.withLock {
                    upstreamCache[channelId] = CachedUpstream(savedAt, manifest)
                }
                staleGoodCacheStore.saveUpstream(channelId, manifest)
                lastUpstreamSuccessMs = savedAt
                return manifest
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: TimeoutCancellationException) {
                lastError = exc
                mirrorLatencyTracker.recordMirrorFailure(baseUrl)
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
                    mirrorLatencyTracker.recordMirrorFailure(baseUrl)
                    connectivityFailures++
                    markMirrorFailure(baseUrl)
                    Log.d(TAG, "Mirror failed for $channelId on $baseUrl: ${exc.message}")
                    continue
                }
                Log.d(TAG, "Non-mirror failure for $channelId on $baseUrl: ${exc.message}")
            }
        }
        serveStaleUpstreamFromCaches(channelId, System.currentTimeMillis())?.let { return it }
        throw lastError ?: IllegalStateException(
            if (isGlobalOutageActive()) "upstream_outage" else "No mirrors available for channel $channelId",
        )
    }

    private suspend fun serveStaleStreamFromCaches(
        cacheKey: String,
        channelId: String,
        now: Long,
    ): String? {
        val staleTtl = staleStreamTtlMs()
        cacheMutex.withLock {
            val stale = staleStreamCache[cacheKey]
            if (stale != null && now - stale.savedAtMs < staleTtl) {
                lastServedFromStaleCache = true
                Log.w(TAG, "cache-serve memory channel=$channelId ageMs=${now - stale.savedAtMs}")
                return stale.rewrittenPlaylist
            }
        }
        val disk = staleGoodCacheStore.loadStream(cacheKey, staleTtl)
        if (disk != null) {
            lastServedFromStaleCache = true
            cacheMutex.withLock {
                val entry = CachedManifest(disk.savedAtMs, disk.playlist)
                staleStreamCache[cacheKey] = entry
            }
            Log.w(TAG, "cache-serve disk channel=$channelId ageMs=${now - disk.savedAtMs}")
            recordHealingAction("stale_disk_serve stream $channelId")
            return disk.playlist
        }
        return null
    }

    private suspend fun serveStaleUpstreamFromCaches(channelId: String, now: Long): UpstreamManifest? {
        val staleTtl = upstreamStaleTtlMs()
        cacheMutex.withLock {
            val stale = upstreamCache[channelId]
            if (stale != null && now - stale.savedAtMs < staleTtl) {
                lastServedFromStaleCache = true
                Log.w(TAG, "cache-serve upstream memory channel=$channelId")
                return stale.manifest
            }
        }
        val disk = staleGoodCacheStore.loadUpstream(channelId, staleTtl)
        if (disk != null) {
            lastServedFromStaleCache = true
            cacheMutex.withLock {
                upstreamCache[channelId] = CachedUpstream(disk.savedAtMs, disk.manifest)
            }
            Log.w(TAG, "cache-serve upstream disk channel=$channelId ageMs=${now - disk.savedAtMs}")
            recordHealingAction("stale_disk_serve upstream $channelId")
            return disk.manifest
        }
        return null
    }

    private fun staleStreamTtlMs(): Long =
        if (isGlobalOutageActive()) {
            GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
        } else {
            GatewayConfig.STALE_STREAM_TTL_MS
        }

    private fun upstreamStaleTtlMs(): Long =
        if (isGlobalOutageActive()) {
            GatewayConfig.OUTAGE_STALE_GRACE_TTL_MS
        } else {
            GatewayConfig.UPSTREAM_STALE_TTL_MS
        }

    private fun mirrorAttemptTimeoutMs(): Long =
        if (isGlobalOutageActive()) {
            GatewayConfig.OUTAGE_MIRROR_ATTEMPT_TIMEOUT_MS
        } else {
            GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS
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
                    channelFromRow(row.channelId, row.channelName, fastPath = true)
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

    private fun orderedMirrorUrls(): List<String> =
        MirrorLatencyTracker.orderedMirrorUrls(
            activeBaseUrl = activeBaseUrl,
            dlhdBaseUrl = environment.dlhdBaseUrl,
            configuredMirrors = environment.mirrorUrls,
            mirrorLatencyMs = mirrorLatencyTracker::mirrorLatencyMs,
            isExcluded = { baseUrl ->
                isMirrorDead(baseUrl) || isMirrorCoolingDown(baseUrl)
            },
        )

    fun mirrorStatsSnapshot(): MirrorStatsSnapshot {
        val hits = streamCacheHits
        val misses = streamCacheMisses
        val total = hits + misses
        return MirrorStatsSnapshot(
            activeBaseUrl = activeBaseUrl,
            fastestMirrorEmaMs = mirrorLatencyTracker.fastestMirrorEmaMs(),
            streamCacheHitRate = if (total > 0) hits.toDouble() / total.toDouble() else null,
            mirrorLatenciesMs = mirrorLatencyTracker.mirrorLatencySnapshot(),
        )
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
            staleDiskEntries = staleGoodCacheStore.entryCount(),
            outageMode = isGlobalOutageActive(),
            cacheServeMode = lastServedFromStaleCache,
            breakerOpen = isGlobalOutageActive(),
            breakerRemainingMs = outageRemainingMs(),
            outageOpenCount = consecutiveOutageOpens,
            lastUpstreamSuccessMs = lastUpstreamSuccessMs.takeIf { it > 0L },
            canary = lastCanarySnapshot,
        )
    }

    suspend fun runCanaryProbes(apiUrl: String): CanarySnapshot {
        val now = System.currentTimeMillis()
        var goodOk = 0
        for (channelId in GatewayConfig.CANARY_GOOD_CHANNEL_IDS) {
            val ok = runCatching {
                withTimeout(GatewayConfig.OUTAGE_PROBE_TIMEOUT_MS) {
                    resolveStream(channelId, useProxy = true, apiUrl = apiUrl)
                }
            }.isSuccess
            if (ok) goodOk++
        }
        var badExpectedFail = 0
        for (channelId in GatewayConfig.CANARY_BAD_CHANNEL_IDS) {
            val failed = runCatching {
                withTimeout(GatewayConfig.OUTAGE_PROBE_TIMEOUT_MS) {
                    resolveStream(channelId, useProxy = true, apiUrl = apiUrl)
                }
            }.isFailure
            if (failed) badExpectedFail++
        }
        val snapshot = CanarySnapshot(
            goodOk = goodOk,
            goodTotal = GatewayConfig.CANARY_GOOD_CHANNEL_IDS.size,
            badExpectedFail = badExpectedFail,
            badTotal = GatewayConfig.CANARY_BAD_CHANNEL_IDS.size,
            lastProbeMs = now,
        )
        lastCanarySnapshot = snapshot
        recordHealingAction(
            "canary_probe good=$goodOk/${snapshot.goodTotal} bad_fail=$badExpectedFail/${snapshot.badTotal}",
        )
        if (goodOk > 0 && isGlobalOutageActive()) {
            clearGlobalOutageIfOpen()
        }
        return snapshot
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
        staleGoodCacheStore.purgeExpired(GatewayConfig.STALE_DISK_TTL_MS)
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
        val probeTimeout = if (isGlobalOutageActive()) {
            GatewayConfig.OUTAGE_PROBE_TIMEOUT_MS
        } else {
            GatewayConfig.MIRROR_ATTEMPT_TIMEOUT_MS
        }
        if (isGlobalOutageActive()) {
            Log.i(TAG, "upstream-outage mode active; running fast mirror probe (${probeTimeout}ms)")
        }
        for (baseUrl in orderedMirrorUrls()) {
            if (isMirrorDead(baseUrl) || isMirrorCoolingDown(baseUrl)) continue
            try {
                val rows = withTimeout(probeTimeout) {
                    fetchChannelRows(baseUrl)
                }
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
        if (!isGlobalOutageActive()) {
            openGlobalOutage("mirror_probe_all_failed")
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
        consecutiveOutageOpens++
        val backoff = min(
            GatewayConfig.OUTAGE_BREAKER_BASE_MS * (1L shl min(consecutiveOutageOpens - 1, 4)),
            GatewayConfig.OUTAGE_BREAKER_MAX_MS,
        )
        outageModeUntilMs = System.currentTimeMillis() + backoff
        recordHealingAction("upstream_outage_open $reason backoffMs=$backoff opens=$consecutiveOutageOpens")
    }

    private fun clearGlobalOutageIfOpen() {
        if (outageModeUntilMs == 0L) return
        outageModeUntilMs = 0L
        consecutiveOutageOpens = 0
        recordHealingAction("upstream_outage_closed")
    }

    fun isGlobalOutageActive(): Boolean = System.currentTimeMillis() < outageModeUntilMs

    fun outageRemainingMs(): Long = (outageModeUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    fun shouldSuppressRestartForOutage(): Boolean = isGlobalOutageActive() || lastServedFromStaleCache

    fun reportHealthyStart() {
        lastServedFromStaleCache = false
        clearGlobalOutageIfOpen()
    }

    fun wasLastServeFromStaleCache(): Boolean = lastServedFromStaleCache

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
                    val cachedLogo = row.optString("logo").takeIf { it.isNotBlank() }
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(channelFromRow(id, name, tvgId, cachedLogo, fastPath = true))
                    }
                }
            }
            if (parsed.isNotEmpty()) {
                channels = parsed
                channelRevision++
            }
            activeBaseUrl = root.optString("base_url", environment.dlhdBaseUrl)
            lastChannelRefreshMs = root.optLong("saved_at", 0L)
        } catch (_: Exception) {
            // Ignore corrupt cache.
        }
    }

    private fun channelFromRow(
        channelId: String,
        channelName: String,
        cachedTvgId: String? = null,
        cachedLogo: String? = null,
        fastPath: Boolean = false,
    ): Channel {
        val id = channelId.trim()
        val upstreamName = channelName.trim().replace("#", "")
        val name = channelNameOverrides.nameFor(id, upstreamName)
        val tags = channelMetaStore?.tagsFor(upstreamName).orEmpty()
            .ifEmpty { channelMetaStore?.tagsFor(name).orEmpty() }
        val mappedId = epgChannelMapper?.tvgIdFor(id, upstreamName)
            ?: epgChannelMapper?.tvgIdFor(id, name)
        val tvgId = mappedId?.takeIf { it.isNotBlank() }
            ?: cachedTvgId?.takeIf { it.isNotBlank() }
            ?: if (fastPath) null else tvgIdResolver?.resolve(name, groupTitle = tags.firstOrNull())?.tvgId
        val logo = cachedLogo?.takeIf { logoResolver?.isPersistedRemoteLogo(it) == true }
        return Channel(
            id = id,
            name = name,
            tags = tags,
            tvgId = tvgId,
            logo = logo,
        )
    }

    private fun saveDiskCache() {
        if (channels.isEmpty()) return
        val channelsArray = org.json.JSONArray()
        channels.forEach { channel ->
            val row = JSONObject()
                .put("id", channel.id)
                .put("name", channel.name)
                .put("tvg_id", channel.tvgId)
            channel.logo?.takeIf { it.isNotBlank() }?.let { row.put("logo", it) }
            channelsArray.put(row)
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
        val staleDiskEntries: Int,
        val outageMode: Boolean,
        val cacheServeMode: Boolean,
        val breakerOpen: Boolean,
        val breakerRemainingMs: Long,
        val outageOpenCount: Int,
        val lastUpstreamSuccessMs: Long?,
        val canary: CanarySnapshot,
    )

    data class CanarySnapshot(
        val goodOk: Int = 0,
        val goodTotal: Int = 0,
        val badExpectedFail: Int = 0,
        val badTotal: Int = 0,
        val lastProbeMs: Long = 0L,
    )

    data class MirrorStatsSnapshot(
        val activeBaseUrl: String,
        val fastestMirrorEmaMs: Double?,
        val streamCacheHitRate: Double?,
        val mirrorLatenciesMs: Map<String, Double>,
    )

    companion object {
        private const val TAG = "DaddyLiveClient"
    }
}
