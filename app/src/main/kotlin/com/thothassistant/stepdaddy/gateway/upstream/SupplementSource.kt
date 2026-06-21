package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.TheTvAppSportsEpgGenerator
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SupplementSource(
    context: Context,
    private val environment: GatewayEnvironment,
    private val nameIndex: IptvOrgNameIndex = IptvOrgNameIndex(context),
    private val httpClient: OkHttpClient = defaultClient(),
    private val sportsResolver: TheTvAppSportsResolver = TheTvAppSportsResolver(httpClient),
    private val iptvOrgEpgRepository: IptvOrgEpgRepository = IptvOrgEpgRepository(context, httpClient),
) {
    private val fastEpgCatalog = FastEpgCatalog(context)
    private val iptvOrgSource = IptvOrgStreamsSource(
        context,
        httpClient,
        fastEpgCatalog = fastEpgCatalog,
        nameIndex = nameIndex,
    )
    private val ntvCxCatalogStore = NtvCxCatalogStore(context)
    private val ntvCxSource = NtvCxCdnLiveSource(
        NtvCxCdnLiveResolver(
            NtvCxCdnLiveResolver.defaultClient(),
            catalogStore = ntvCxCatalogStore,
        ),
    )

    private val adultSwimSource = AdultSwimStreamsSource(AdultSwimStreamsSource.defaultClient())

    data class SyncSnapshot(
        val blockedTheTvApp: Int = 0,
        val blockedTvPass: Int = 0,
        val blockedTokenProxy: Int = 0,
        val moveOnJoyChannels: Int = 0,
        val sportsChannels: Int = 0,
        val sportsEventsScanned: Int = 0,
        val iptvOrgChannels: Int = 0,
        val iptvOrgPlaylistsFetched: Int = 0,
        val iptvOrgPlaylistsFailed: Int = 0,
        val iptvOrgEntriesParsed: Int = 0,
        val ntvCxChannels: Int = 0,
        val ntvCxResolveProbeOk: Boolean = false,
        val adultSwimChannels: Int = 0,
        val adultSwimProbed: Int = 0,
        val adultSwimProbeOk: Int = 0,
    )

    private val store = SupplementStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    @Volatile
    private var refreshInFlight = false
    @Volatile
    private var cached: List<SupplementChannel> = store.readChannels()
    @Volatile
    private var lastSync = SyncSnapshot()

    @Volatile
    var onRefreshComplete: (() -> Unit)? = null

    fun enabled(): Boolean =
        environment.supplementBaseUrl.isNotBlank() ||
            environment.supplementSportsEnabled ||
            environment.supplementIptvOrgEnabled ||
            environment.supplementNtvCxEnabled ||
            environment.supplementAdultSwimEnabled

    fun sidecarEnabled(): Boolean = environment.supplementBaseUrl.isNotBlank()

    fun sportsEnabled(): Boolean = environment.supplementSportsEnabled

    fun iptvOrgEnabled(): Boolean = environment.supplementIptvOrgEnabled

    fun ntvCxEnabled(): Boolean = environment.supplementNtvCxEnabled

    fun adultSwimEnabled(): Boolean = environment.supplementAdultSwimEnabled

    fun adultSwimImportMode(): SupplementImportMode = environment.supplementAdultSwimImportMode

    fun ntvCxImportMode(): SupplementImportMode = environment.supplementNtvCxImportMode

    /** @deprecated use [ntvCxImportMode] */
    fun ntvCxMergeMode(): SupplementImportMode = ntvCxImportMode()

    fun channels(): List<SupplementChannel> = cached

    fun channelCount(): Int = cached.size

    fun moveOnJoyCount(): Int = cached.count { it.id.startsWith("sup:") }

    fun sportsCount(): Int = cached.count { it.id.startsWith("sport:") }

    fun iptvOrgCount(): Int = cached.count { it.id.startsWith("iptv:") }

    fun ntvCxCount(): Int = cached.count { it.id.startsWith("ntv:") }

    fun adultSwimCount(): Int = cached.count { it.id.startsWith("adultswim:") }

    fun ntvChannel(token: String): SupplementChannel? =
        cached.firstOrNull { it.id == "ntv:$token" }

    fun syncSnapshot(): SyncSnapshot = lastSync

    fun epgXmlFile(): File? = store.epgFile()

    fun fastEpgFeedFiles(): List<File> {
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return emptyList()
        return fastEpgCatalog.cachedFeedFiles()
    }

    /** Download FAST provider guides if missing; backfill mjh hash tvg-ids on cached iptv-org rows. */
    fun prepareFastEpgForBuild(): Int {
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return 0
        runCatching {
            fastEpgCatalog.refresh(
                force = fastEpgCatalog.isStale() || fastEpgCatalog.cachedFeedFiles().isEmpty(),
            )
        }.onFailure { exc ->
            Log.w(TAG, "FAST EPG refresh failed", exc)
            return 0
        }
        return backfillFastTvgIdsFromCatalog()
    }

    private fun backfillFastTvgIdsFromCatalog(): Int {
        if (cached.isEmpty()) return 0
        var updated = 0
        val next = cached.map { channel ->
            if (!channel.id.startsWith("iptv:")) return@map channel
            val provider = channel.providerTag?.takeIf { it.isNotEmpty() } ?: return@map channel
            val hashId = fastEpgCatalog.lookupChannelId(channel.name, provider) ?: return@map channel
            val current = channel.tvgId?.trim().orEmpty()
            if (current == hashId) return@map channel
            if (current.isEmpty() || current.contains('.')) {
                updated++
                channel.copy(tvgId = hashId)
            } else {
                channel
            }
        }
        if (updated > 0) {
            cached = next
            store.writeChannels(next)
            Log.i(TAG, "FAST EPG backfill: updated $updated iptv-org tvg-ids")
        }
        return updated
    }

    fun iptvOrgEpgFile(): File? {
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return null
        return iptvOrgEpgRepository.mergedGuideFile()
    }

    fun sportsEpgXmlFile(): File? {
        val file = store.sportsEpgFile
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun sportsTvgIdsForEpg(): Set<String> =
        cached.filter { it.id.startsWith("sport:") }
            .mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

    fun fastTvgIdsForEpg(): Set<String> =
        cached.filter { it.id.startsWith("iptv:") }
            .mapNotNull { channel ->
                channel.tvgId?.trim()?.takeIf { id ->
                    id.isNotEmpty() && !id.contains('.')
                }
            }
            .toSet()

    fun tvgIdsForEpg(): Set<String> =
        cached.mapNotNull { channel ->
            channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()

    fun iptvOrgTvgIdsForEpg(): Set<String> =
        cached.filter { it.id.startsWith("iptv:") }
            .mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

    fun lastSyncedAtMs(): Long = store.lastSyncedAtMs()

    fun scheduleRefresh(daddyChannels: List<Channel>, force: Boolean = false) {
        if (!enabled()) {
            if (cached.isNotEmpty()) {
                cached = emptyList()
                store.writeChannels(emptyList())
                lastSync = SyncSnapshot()
            }
            return
        }
        if (refreshInFlight) return
        if (!force && !store.isStale() && cached.isNotEmpty()) return
        scope.launch {
            refresh(daddyChannels, force = force)
        }
    }

    fun schedulePeriodicRefresh(channelProvider: () -> List<Channel>) {
        scope.launch {
            delay(60_000)
            while (isActive) {
                if (enabled()) {
                    refresh(channelProvider(), force = store.isStale())
                }
                delay(SupplementConfig.SYNC_INTERVAL_MS)
            }
        }
    }

    suspend fun refresh(daddyChannels: List<Channel>, force: Boolean = false) {
        if (!enabled()) return
        refreshMutex.withLock {
            if (refreshInFlight) return
            if (!force && !store.isStale() && cached.isNotEmpty()) return
            refreshInFlight = true
            try {
                val supplements = withContext(Dispatchers.IO) {
                    mergeSupplements(daddyChannels)
                }
                cached = supplements
                store.writeChannels(supplements)
                Log.i(
                    TAG,
                    "Supplement sync: ${supplements.size} total " +
                        "(moveonjoy=${moveOnJoyCount()}, sports=${sportsCount()}, " +
                        "iptv-org=${iptvOrgCount()}, ntv.cx=${ntvCxCount()}, " +
                        "adultswim=${adultSwimCount()}, " +
                        "blocked_thetvapp=${lastSync.blockedTheTvApp})",
                )
                onRefreshComplete?.invoke()
            } catch (exc: Exception) {
                Log.w(TAG, "Supplement sync failed — keeping cache", exc)
                if (cached.isEmpty()) {
                    cached = store.readChannels()
                } else {
                    Unit
                }
            } finally {
                refreshInFlight = false
            }
        }
    }

    private suspend fun mergeSupplements(daddyChannels: List<Channel>): List<SupplementChannel> =
        coroutineScope {
        var filterResult = SupplementProviderFilter.Result(allowed = emptyList())
        val sidecar = if (sidecarEnabled()) {
            val base = environment.supplementBaseUrl.trimEnd('/')
            val m3uText = downloadText(
                SupplementConfig.playlistUrl(base),
                SupplementConfig.MAX_M3U_BYTES,
            )
            if (m3uText != null) {
                downloadEpg(base)
                val entries = M3uParser.parse(m3uText)
                filterResult = SupplementProviderFilter.filter(entries)
                SupplementDedup.filterNewChannels(
                    entries = filterResult.allowed,
                    daddyChannels = daddyChannels,
                    maxChannels = SupplementConfig.MAX_CHANNELS,
                    importMode = environment.supplementSidecarImportMode,
                )
            } else {
                Log.w(TAG, "Sidecar playlist fetch failed — skipping TVApp2 supplement")
                emptyList()
            }
        } else {
            emptyList()
        }

        val sports = if (sportsEnabled()) {
            runCatching { sportsResolver.resolveFromNetwork() }
                .getOrElse { exc ->
                    Log.w(TAG, "Sports resolver failed", exc)
                    emptyList<SupplementChannel>() to TheTvAppSportsResolver.ResolveStats()
                }
                .let { (channels, _) ->
                    val live = channels.take(SupplementConfig.MAX_SPORTS_EVENTS)
                    if (live.isNotEmpty()) {
                        runCatching {
                            TheTvAppSportsEpgGenerator.writeXml(
                                TheTvAppSportsEpgGenerator.programmesForChannels(live),
                                store.sportsEpgFile,
                            )
                        }.onFailure { exc ->
                            Log.w(TAG, "Sports EPG write failed", exc)
                        }
                    }
                    live
                }
        } else {
            emptyList()
        }

        if (iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
            runCatching {
                fastEpgCatalog.refresh(
                    force = fastEpgCatalog.isStale() || fastEpgCatalog.cachedFeedFiles().isEmpty(),
                )
            }.onFailure { exc -> Log.w(TAG, "FAST EPG refresh failed", exc) }
        }

        val iptvOrgDeferred = async {
            if (iptvOrgEnabled()) {
                runCatching { iptvOrgSource.fetchChannels(daddyChannels, environment.supplementIptvOrgImportMode) }
                    .getOrElse { exc ->
                        Log.w(TAG, "iptv-org fetch failed", exc)
                        emptyList<SupplementChannel>() to IptvOrgStreamsSource.FetchStats()
                    }
            } else {
                emptyList<SupplementChannel>() to IptvOrgStreamsSource.FetchStats()
            }
        }

        val ntvCxDeferred = async {
            if (ntvCxEnabled()) {
                runCatching {
                    ntvCxSource.fetchChannels(
                        daddyChannels,
                        environment.supplementNtvCxImportMode,
                        nameIndex,
                    )
                }
                    .getOrElse { exc ->
                        Log.w(TAG, "ntv.cx 24/7 fetch failed", exc)
                        emptyList<SupplementChannel>() to NtvCxCdnLiveResolver.FetchStats()
                    }
            } else {
                emptyList<SupplementChannel>() to NtvCxCdnLiveResolver.FetchStats()
            }
        }

        val adultSwimDeferred = async {
            if (adultSwimEnabled()) {
                runCatching {
                    adultSwimSource.fetchChannels(
                        daddyChannels,
                        environment.supplementAdultSwimImportMode,
                    )
                }
                    .getOrElse { exc ->
                        Log.w(TAG, "adult swim fetch failed", exc)
                        emptyList<SupplementChannel>() to AdultSwimStreamsSource.FetchStats()
                    }
            } else {
                emptyList<SupplementChannel>() to AdultSwimStreamsSource.FetchStats()
            }
        }

        val (iptvOrg, iptvStats) = iptvOrgDeferred.await()
        var (ntvCx, ntvStats) = ntvCxDeferred.await()
        var (adultSwim, adultSwimStats) = adultSwimDeferred.await()

        if (ntvCx.isEmpty() && ntvCxEnabled()) {
            val cachedNtv = cached.filter { it.id.startsWith("ntv:") }
            if (cachedNtv.isNotEmpty()) {
                Log.w(TAG, "ntv.cx fetch empty — keeping ${cachedNtv.size} cached channels")
                ntvCx = cachedNtv
            }
        }

        if (adultSwim.isEmpty() && adultSwimEnabled()) {
            val cachedAdultSwim = cached.filter { it.id.startsWith("adultswim:") }
            if (cachedAdultSwim.isNotEmpty()) {
                Log.w(TAG, "adult swim probe empty — keeping ${cachedAdultSwim.size} cached channels")
                adultSwim = cachedAdultSwim
            }
        }

        if (iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
            runCatching {
                iptvOrgEpgRepository.refresh(
                    environment.iptvOrgEpgUrl.takeIf { it.isNotBlank() },
                )
            }.onFailure { exc -> Log.w(TAG, "iptv-org EPG refresh failed", exc) }
        }

        lastSync = SyncSnapshot(
            blockedTheTvApp = filterResult.blockedTheTvApp,
            blockedTvPass = filterResult.blockedTvPass,
            blockedTokenProxy = filterResult.blockedTokenProxy,
            moveOnJoyChannels = sidecar.size,
            sportsChannels = sports.size,
            iptvOrgChannels = iptvOrg.size,
            iptvOrgPlaylistsFetched = iptvStats.playlistsFetched,
            iptvOrgPlaylistsFailed = iptvStats.playlistsFailed,
            iptvOrgEntriesParsed = iptvStats.entriesParsed,
            ntvCxChannels = ntvCx.size,
            ntvCxResolveProbeOk = ntvStats.resolveProbeOk,
            adultSwimChannels = adultSwim.size,
            adultSwimProbed = adultSwimStats.probed,
            adultSwimProbeOk = adultSwimStats.probeOk,
        )

        sidecar + sports + iptvOrg + ntvCx + adultSwim
    }

    private fun downloadEpg(base: String) {
        val gzipOk = downloadToFile(
            SupplementConfig.epgGzipUrl(base),
            store.epgGzipFile,
            SupplementConfig.MAX_EPG_BYTES,
        )
        if (gzipOk) {
            store.epgPlainFile.delete()
            return
        }
        downloadToFile(
            SupplementConfig.epgXmlUrl(base),
            store.epgPlainFile,
            SupplementConfig.MAX_EPG_BYTES,
        )
    }

    private fun downloadText(url: String, maxBytes: Int): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.bytes()
            if (bytes.size > maxBytes) return null
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun downloadToFile(url: String, target: File, maxBytes: Int): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
            .build()
        val tmp = File(target.parentFile, "${target.name}.part")
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                var total = 0L
                tmp.outputStream().use { sink ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) error("supplement_epg_too_large")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    companion object {
        private const val TAG = "SupplementSource"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(SupplementConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(SupplementConfig.DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
