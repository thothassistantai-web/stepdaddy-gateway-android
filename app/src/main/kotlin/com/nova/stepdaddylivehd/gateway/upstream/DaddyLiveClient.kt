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
import java.util.concurrent.TimeUnit

class DaddyLiveClient(
    private val environment: GatewayEnvironment,
    private val epgChannelMapper: EpgChannelMapper? = null,
    private val logoResolver: LogoResolver? = null,
    private val resportzParser: ResportzParser = ResportzParser(),
    private val client: OkHttpClient = ResportzParser.defaultClient(),
    context: Context,
) {
    private val prefs = context.getSharedPreferences("stepdaddy_channels", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val cacheMutex = Mutex()
    private val upstreamFetchSem = Semaphore(GatewayConfig.UPSTREAM_FETCH_MAX_CONCURRENT)
    @Volatile
    private var refreshInFlight = false
    private val streamCache = mutableMapOf<String, CachedManifest>()
    private val staleStreamCache = mutableMapOf<String, CachedManifest>()
    private val upstreamCache = mutableMapOf<String, CachedUpstream>()
    private val deadMirrors = mutableMapOf<String, Long>()

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
            return rewritten
        } catch (exc: Exception) {
            if (exc is CancellationException) {
                throw exc
            }
            cacheMutex.withLock {
                val stale = staleStreamCache[cacheKey]
                if (stale != null && now - stale.savedAtMs < GatewayConfig.STALE_STREAM_TTL_MS) {
                    Log.w(TAG, "Serving stale stream for $channelId after ${exc.message}")
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
                if (stale != null && now - stale.savedAtMs < GatewayConfig.UPSTREAM_STALE_TTL_MS) {
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
        for (baseUrl in orderedMirrorUrls()) {
            if (isMirrorDead(baseUrl)) continue
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
                Log.d(TAG, "Mirror timeout for $channelId on $baseUrl (${attemptBudget}ms)")
            } catch (exc: Exception) {
                lastError = exc
                markMirrorDead(baseUrl)
                Log.d(TAG, "Mirror failed for $channelId on $baseUrl: ${exc.message}")
            }
        }
        cacheMutex.withLock {
            val stale = upstreamCache[channelId]
            if (stale != null && System.currentTimeMillis() - stale.savedAtMs < GatewayConfig.UPSTREAM_STALE_TTL_MS) {
                Log.w(TAG, "Serving stale upstream for $channelId after mirror failures")
                return stale.manifest
            }
        }
        throw lastError ?: IllegalStateException("No mirrors available for channel $channelId")
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
                    val id = row.channelId.trim()
                    val name = row.channelName.trim().replace("#", "")
                    val tvgId = epgChannelMapper?.tvgIdFor(id, name)
                    Channel(
                        id = id,
                        name = name,
                        tvgId = tvgId,
                        logo = logoResolver?.resolveLogoUrl(environment.loopbackBase(), name, tvgId),
                    )
                }.sortedWith(compareBy({ it.name.startsWith("18") }, { it.name }))
            } catch (exc: Exception) {
                lastError = exc
                markMirrorDead(baseUrl)
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
        deadMirrors[baseUrl.trimEnd('/')] = System.currentTimeMillis()
    }

    private fun markMirrorAlive(baseUrl: String) {
        deadMirrors.remove(baseUrl.trimEnd('/'))
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
                        add(
                            Channel(
                                id = id,
                                name = name,
                                tvgId = tvgId,
                                logo = logoResolver?.resolveLogoUrl(environment.loopbackBase(), name, tvgId),
                            ),
                        )
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

    companion object {
        private const val TAG = "DaddyLiveClient"
    }
}
