package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.FireTvDevice
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.FastChannelTvgIdResolver
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.EventMetadata
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import java.io.File
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class SupplementSource(
    context: Context,
    private val environment: GatewayEnvironment,
    private val nameIndex: IptvOrgNameIndex = IptvOrgNameIndex(context),
    private val epgChannelMapper: EpgChannelMapper? = null,
    private val logoResolver: LogoResolver? = null,
    private val channelMetaStore: ChannelMetaStore? = null,
    private val httpClient: OkHttpClient = SupplementConfig.defaultHttpClient(),
    private val iptvOrgEpgRepository: IptvOrgEpgRepository = IptvOrgEpgRepository(context, httpClient),
) {
    private val appContext = context.applicationContext
    private val fastEpgCatalog = FastEpgCatalog(context)
    private val fastChannelTvgIdResolver = FastChannelTvgIdResolver(fastEpgCatalog, epgChannelMapper)
    private val iptvOrgSource = IptvOrgStreamsSource(
        context,
        httpClient,
        fastEpgCatalog = fastEpgCatalog,
        fastChannelTvgIdResolver = fastChannelTvgIdResolver,
        nameIndex = nameIndex,
        lowRamDevice = FireTvDevice.isFireTv(context) ||
            com.thothassistant.stepdaddy.gateway.LowRamTvDevice.needsMemoryLite(context),
    )
    private val ntvCxCatalogStore = NtvCxCatalogStore(context)
    private val ntvCxSource = NtvCxCdnLiveSource(
        NtvCxCdnLiveResolver(
            NtvCxCdnLiveResolver.defaultClient(),
            catalogStore = ntvCxCatalogStore,
        ),
    )

    private val adultSwimSource = AdultSwimStreamsSource(AdultSwimStreamsSource.defaultClient())
    private val freeTvSource = FreeTvIptvSource(httpClient)
    private val duloCxResolver = DuloCxLiveResolver(
        DuloCxLiveResolver.defaultClient(),
        accessTokenProvider = { environment.supplementDuloCxAccessToken },
    )
    private val duloCxSource = DuloCxLiveSource(duloCxResolver)
    private val tmdbVodCatalogStore = TmdbVodCatalogStore(context)
    private val tmdbVodCatalog = TmdbVodCatalog(context, httpClient, apiKey = { environment.effectiveTmdbApiKey() })
    private val tmdbVodSource = TmdbVodSource(tmdbVodCatalog, tmdbVodCatalogStore)
    private val tmdbVodSeriesCatalogStore = TmdbVodSeriesCatalogStore(context)
    private val tmdbVodSeriesCatalog = TmdbVodSeriesCatalog(context, httpClient)
    private val tmdbVodSeriesSource = TmdbVodSeriesSource(tmdbVodSeriesCatalog, tmdbVodSeriesCatalogStore)
    private val dlhdEventResolver = DaddyLiveEventResolver(httpClient)
    private val dlhdEventHealthStore = DlhdEventStreamHealthStore()
    private val dlhdEventActiveMirrorStore = DlhdEventActiveMirrorStore()
    private val dlhdEventMirrorProbeStore = DlhdEventMirrorProbeStore()
    private val dlhdEventProber = DlhdEventStreamProber(
        httpClient = httpClient,
        mirrorProbeStore = dlhdEventMirrorProbeStore,
    )
    private val eventStreamHealthMonitor by lazy {
        EventStreamHealthMonitor(
            channelProvider = { cached.filter { it.id.startsWith("dlhd-event:") } },
            store = dlhdEventHealthStore,
            prober = dlhdEventProber,
            mirrorProbeStore = dlhdEventMirrorProbeStore,
            sportsEnabled = { sportsEnabled() },
            onStatesChanged = { onDlhdEventHealthChanged?.invoke() },
        )
    }

    /** Fire Stick only: skip loading ~4k supplement rows at construct (tens of MB, trips LMK). */
    private val fireLite = FireTvDevice.isFireTv(appContext)
    private val store = SupplementStore(context)
    private val consolidationOverrides = ConsolidationOverrideStore(context)
    private val eventMetadataStore = EventMetadataStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val specialEventsMutex = Mutex()
    @Volatile private var refreshInFlight = false
    @Volatile private var specialEventsRefreshInFlight = false
    @Volatile private var lastSpecialEventsSyncMs = 0L
    @Volatile private var lastVerifyTriggeredSyncMs = 0L
    @Volatile private var cached: List<SupplementChannel> =
        if (fireLite) emptyList() else store.readChannels().filterNot { it.id.startsWith("sup:") }
    @Volatile private var daddyChannelFallbacks: Map<String, List<SupplementFallbackMirror>> =
        if (fireLite) {
            emptyMap()
        } else {
            SupplementFallbackOverridesApplier.apply(
                store.readDaddyFallbacks(),
                consolidationOverrides.current(),
            )
        }
    @Volatile private var guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>> =
        if (fireLite) emptyMap() else store.readGuideSchedules()
    @Volatile private var lastSync = SupplementSyncSnapshot()
    @Volatile private var eventMetadata: Map<String, EventMetadata> =
        if (fireLite) emptyMap() else eventMetadataStore.readAll()

    init {
        if (fireLite) {
            Log.i(TAG, "Fire Stick: deferred supplement disk load (memory)")
        } else if (sportsEnabled()) {
            pruneExpiredSpecialEvents()
            if (sportsCount() > 0) {
                lastSpecialEventsSyncMs = store.guideSchedulesSyncedAtMs()
                    .takeIf { it > 0L } ?: store.lastSyncedAtMs()
                if (eventMetadata.isEmpty()) {
                    syncEventMetadata(cached.filter { EventLifecycleManager.isSpecialEventChannel(it.id) })
                }
            }
        }
    }

    /** Drop in-memory supplement catalog under LMK pressure (Fire Stick). */
    fun releaseMemory() {
        if (!fireLite) return
        cached = emptyList()
        daddyChannelFallbacks = emptyMap()
        guideSchedules = emptyMap()
        eventMetadata = emptyMap()
    }

    @Volatile var onRefreshComplete: (() -> Unit)? = null
    @Volatile var onSpecialEventsChanged: (() -> Unit)? = null

    fun enabled(): Boolean =
        environment.supplementSportsEnabled ||
            environment.supplementIptvOrgEnabled ||
            environment.supplementFreeTvEnabled ||
            environment.supplementDuloCxEnabled ||
            environment.supplementNtvCxEnabled ||
            environment.supplementAdultSwimEnabled ||
            environment.supplementTmdbMoviesEnabled

    fun sportsEnabled(): Boolean = environment.supplementSportsEnabled

    fun iptvOrgEnabled(): Boolean = environment.supplementIptvOrgEnabled

    fun freeTvEnabled(): Boolean = environment.supplementFreeTvEnabled

    fun duloCxEnabled(): Boolean = environment.supplementDuloCxEnabled

    fun ntvCxEnabled(): Boolean = environment.supplementNtvCxEnabled

    fun adultSwimEnabled(): Boolean = environment.supplementAdultSwimEnabled

    fun tmdbMoviesEnabled(): Boolean = environment.supplementTmdbMoviesEnabled

    fun adultSwimImportMode(): SupplementImportMode = environment.supplementAdultSwimImportMode

    fun freeTvImportMode(): SupplementImportMode = environment.supplementFreeTvImportMode

    fun duloCxImportMode(): SupplementImportMode = environment.supplementDuloCxImportMode

    fun ntvCxImportMode(): SupplementImportMode = environment.supplementNtvCxImportMode

    /** @deprecated use [ntvCxImportMode] */
    fun ntvCxMergeMode(): SupplementImportMode = ntvCxImportMode()

    fun channels(): List<SupplementChannel> = cached

    fun channelById(id: String): SupplementChannel? = cached.firstOrNull { it.id == id }

    fun daddyChannelFallbacks(channelId: String): List<SupplementFallbackMirror> =
        daddyChannelFallbacks[channelId].orEmpty()

    fun daddyChannelFallbacksAll(): Map<String, List<SupplementFallbackMirror>> = daddyChannelFallbacks

    fun consolidationOverrideStore(): ConsolidationOverrideStore = consolidationOverrides

    /**
     * Re-apply persisted manual attachments / denylist onto the last auto-merged fallbacks
     * and publish the result (also used by the Channel Backups UI).
     */
    fun republishDaddyFallbacks(autoFallbacks: Map<String, List<SupplementFallbackMirror>>? = null) {
        val base = autoFallbacks ?: store.readDaddyFallbacks()
        val applied = SupplementFallbackOverridesApplier.apply(base, consolidationOverrides.current())
        daddyChannelFallbacks = applied
        store.writeDaddyFallbacks(applied)
    }

    fun removeDaddyFallback(
        daddyChannelId: String,
        mirror: SupplementFallbackMirror,
        denyFutureAutoMatch: Boolean,
    ) {
        if (denyFutureAutoMatch) {
            consolidationOverrides.denyPair(daddyChannelId, mirror)
        }
        consolidationOverrides.removeManualAttachment(daddyChannelId, mirror)
        val next = daddyChannelFallbacks.toMutableMap()
        val remaining = next[daddyChannelId].orEmpty().filterNot {
            SupplementMatchScorer.mirrorFingerprint(it) == SupplementMatchScorer.mirrorFingerprint(mirror)
        }
        if (remaining.isEmpty()) next.remove(daddyChannelId) else next[daddyChannelId] = remaining
        daddyChannelFallbacks = next
        store.writeDaddyFallbacks(next)
    }

    fun attachManualDaddyFallback(
        daddyChannelId: String,
        mirror: SupplementFallbackMirror,
        supplementName: String = "",
        supplementSource: String = "",
        country: String = "",
    ) {
        consolidationOverrides.addManualAttachment(
            daddyChannelId = daddyChannelId,
            mirror = mirror,
            supplementName = supplementName,
            supplementSource = supplementSource,
            country = country,
        )
        republishDaddyFallbacks()
    }

    fun channelCount(): Int = cached.size

    fun sportsCount(): Int = cached.count {
        it.id.startsWith("dlhd-guide:") ||
            it.id.startsWith("dlhd-event:")
    }

    fun specialEventGuideCount(): Int = cached.count { it.id.startsWith("dlhd-guide:") }

    fun dlhdEventStreamCount(): Int = cached.count { it.id.startsWith("dlhd-event:") }

    fun iptvOrgCount(): Int = cached.count { it.id.startsWith("iptv:") }

    fun ntvCxCount(): Int = cached.count { it.id.startsWith("ntv:") }

    fun adultSwimCount(): Int = cached.count { it.id.startsWith("adultswim:") }

    fun freeTvCount(): Int = cached.count { it.id.startsWith(FreeTvIptvConfig.ID_PREFIX) }

    fun duloCxCount(): Int = cached.count { it.id.startsWith(DuloCxLiveConfig.ID_PREFIX) }

    fun tmdbVodCount(): Int = cached.count { it.id.startsWith(TmdbVodConfig.ID_PREFIX) }

    fun tmdbVodSeriesCount(): Int = cached.count { it.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) }

    fun vodMovie(tmdbId: String): SupplementChannel? =
        cached.firstOrNull { it.id == "${TmdbVodConfig.ID_PREFIX}$tmdbId" }

    fun vodEpisode(showTmdbId: String, season: Int, episode: Int): SupplementChannel? {
        val showId = showTmdbId.trim().toIntOrNull() ?: return null
        return cached.firstOrNull {
            it.id == TmdbVodConfig.seriesSupplementId(showId, season, episode)
        }
    }

    fun vodEpisodeOrCached(showTmdbId: String, season: Int, episode: Int): SupplementChannel? {
        vodEpisode(showTmdbId, season, episode)?.let { return it }
        val showId = showTmdbId.trim().toIntOrNull() ?: return null
        return tmdbVodSeriesCatalogStore.read()
            .firstOrNull {
                it.showTmdbId == showId && it.season == season && it.episode == episode
            }
            ?.let { tmdbVodSeriesSource.toSupplementChannel(it) }
    }

    fun vodMovieOrCached(tmdbId: String): SupplementChannel? {
        vodMovie(tmdbId)?.let { return it }
        return tmdbVodCatalogStore.read()
            .firstOrNull { it.tmdbId.toString() == tmdbId }
            ?.let { tmdbVodSource.toSupplementChannel(it) }
    }

    /** Newest-first sort key for Xtream series list (show release year). */
    fun vodShowSortYear(showTmdbId: Int): Int =
        tmdbVodSeriesCatalogStore.read()
            .asSequence()
            .filter { it.showTmdbId == showTmdbId }
            .maxOfOrNull { VodSort.movieSortKey(it.showYear, it.showTitle) }
            ?: 0

    fun ntvChannel(token: String): SupplementChannel? =
        cached.firstOrNull { it.id == "ntv:$token" }

    fun duloChannel(channelUuid: String): SupplementChannel? =
        cached.firstOrNull {
            it.id == "${DuloCxLiveConfig.ID_PREFIX}$channelUuid" ||
                it.duloChannelId == channelUuid
        }

    fun syncSnapshot(): SupplementSyncSnapshot = lastSync

    fun syncInFlight(): Boolean = refreshInFlight

    fun specialEventsSyncInFlight(): Boolean = specialEventsRefreshInFlight

    fun specialEventsLastSyncMs(): Long = lastSpecialEventsSyncMs.takeIf { it > 0L } ?: 0L

    fun epgXmlFile(): File? = store.epgFile()

    fun fastEpgFeedFiles(): List<File> {
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return emptyList()
        return fastEpgCatalog.cachedFeedFiles()
    }

    /** Load FAST index from disk (non-blocking); kick background refresh; backfill iptv-org tvg-ids. */
    fun prepareFastEpgForBuild(): Int {
        applyNameEpgOverrides()
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return 0
        runCatching {
            fastEpgCatalog.loadIndexFromDisk()
            val staleOrEmpty =
                fastEpgCatalog.isStale() || fastEpgCatalog.cachedFeedFiles().isEmpty()
            if (staleOrEmpty || !fastEpgCatalog.hasUsableIndex()) {
                scope.launch {
                    runCatching {
                        fastEpgCatalog.refreshAsync(force = staleOrEmpty)
                    }.onFailure { exc -> Log.w(TAG, "FAST EPG background refresh failed", exc) }
                }
            }
        }.onFailure { exc ->
            Log.w(TAG, "FAST EPG disk load failed", exc)
            return 0
        }
        return backfillFastTvgIdsFromCatalog()
    }

    /** Apply bundled/runtime EPG name overrides to cached supplement rows. */
    fun applyNameEpgOverrides(): Int {
        val mapper = epgChannelMapper ?: return 0
        if (cached.isEmpty()) return 0
        var updated = 0
        val next = cached.map { channel ->
            val override = mapper.tvgIdForName(channel.name)?.trim().orEmpty()
            if (override.isEmpty() || override == channel.tvgId?.trim()) return@map channel
            updated++
            channel.copy(tvgId = override)
        }
        if (updated > 0) {
            cached = next
            store.writeChannels(next)
            Log.i(TAG, "EPG name overrides applied to $updated supplement channels")
        }
        return updated
    }

    private fun backfillFastTvgIdsFromCatalog(): Int {
        if (cached.isEmpty()) return 0
        var updated = 0
        val next = cached.map { channel ->
            if (!channel.id.startsWith("iptv:")) return@map channel
            val fixed = fastChannelTvgIdResolver.validateAndFix(
                currentTvgId = channel.tvgId,
                displayName = channel.name,
                groupTitle = channel.groupTitle,
                providerTag = channel.providerTag,
            ) ?: return@map channel
            updated++
            channel.copy(tvgId = fixed)
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
        cached.filter {
            it.id.startsWith("dlhd-guide:") ||
                it.id.startsWith("dlhd-event:")
        }
            .mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

    fun dlhdEventChannel(token: String): SupplementChannel? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        return cached.firstOrNull {
            it.id == "dlhd-event:$trimmed" || it.id.removePrefix("dlhd-event:") == trimmed
        }
    }

    fun dlhdEventStreamHealth(token: String) = dlhdEventHealthStore.status(token)
    fun isDlhdEventStreamHealthy(token: String) = dlhdEventHealthStore.isHealthy(token)
    fun dlhdEventStreamHealthSummary() = dlhdEventHealthStore.summary(dlhdEventStreamCount())
    fun recordDlhdEventStreamHealth(token: String, result: DlhdEventStreamHealth.ProbeResult) {
        val before = dlhdEventHealthStore.revision()
        dlhdEventHealthStore.record(token, result)
        if (dlhdEventHealthStore.revision() != before) onDlhdEventHealthChanged?.invoke()
    }
    fun dlhdEventHealthStore() = dlhdEventHealthStore
    fun dlhdEventActiveMirrorStore() = dlhdEventActiveMirrorStore
    fun dlhdEventMirrorProbeStore() = dlhdEventMirrorProbeStore
    fun specialEventsMirrorSummary() = SpecialEventsMirrorHealth.summarize(
        channels = cached,
        activeMirrorIndexByEvent = dlhdEventActiveMirrorStore.snapshot(),
        eventHealthByKey = dlhdEventHealthStore.snapshot().mapValues { it.value.status },
        mirrorProbeStore = dlhdEventMirrorProbeStore,
    )
    fun eventStreamHealthMonitor() = eventStreamHealthMonitor
    var onDlhdEventHealthChanged: (() -> Unit)? = null
    fun scheduleDlhdEventStreamHealthProbes(tvStreamProbe: suspend (channelId: String) -> Boolean) {
        eventStreamHealthMonitor.start(tvStreamProbe)
    }
    fun stopDlhdEventStreamHealthProbes() = eventStreamHealthMonitor.stop()

    fun dlhdGuideChannel(slug: String): SupplementChannel? {
        val normalized = slug.trim().trim('/')
        if (normalized.isEmpty()) return null
        return cached.firstOrNull {
            it.id == "dlhd-guide:$normalized" || it.id.removePrefix("dlhd-guide:") == normalized
        }
    }

    fun eventMetadata(channelId: String) = eventMetadata[channelId.trim()]
    fun eventMetadataMap() = eventMetadata
    fun eventMetadataCount() = eventMetadata.size
    fun guideSchedule(guideId: String) = guideSchedules[guideId].orEmpty()
    fun guideScheduleContentKey(guideId: String) = GuideScheduleMediaCache.contentKey(
        events = guideSchedule(guideId),
        syncedAtMs = store.lastSyncedAtMs(),
    )

    fun fastTvgIdsForEpg(): Set<String> =
        cached.filter { it.id.startsWith("iptv:") }
            .mapNotNull { channel ->
                channel.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() && !id.contains('.') }
            }
            .toSet()

    fun tvgIdsForEpg(): Set<String> =
        cached.mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }.toSet()

    fun iptvOrgTvgIdsForEpg(): Set<String> =
        cached.filter { it.id.startsWith("iptv:") }
            .mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

    fun lastSyncedAtMs() = store.lastSyncedAtMs()

    suspend fun reEnrichLogos(): CatalogLogoEnricher.Result {
        val enricher = catalogLogoEnricher() ?: return CatalogLogoEnricher.Result(0, 0, 0)
        val (enriched, result) = enricher.enrichSupplements(cached)
        if (result.assigned > 0) {
            cached = enriched
            store.writeChannels(enriched)
        }
        return result
    }

    private fun enrichSupplementLogos(supplements: List<SupplementChannel>): List<SupplementChannel> {
        val enricher = catalogLogoEnricher() ?: return supplements
        return enricher.enrichSupplements(supplements).first
    }

    private fun catalogLogoEnricher(): CatalogLogoEnricher? {
        if (logoResolver == null) return null
        return CatalogLogoEnricher(logoResolver, channelMetaStore)
    }

    fun scheduleRefresh(daddyChannels: List<Channel>, force: Boolean = false) {
        if (!enabled()) {
            if (cached.isNotEmpty()) {
                cached = emptyList()
                guideSchedules = emptyMap()
                store.writeChannels(emptyList())
                store.writeDaddyFallbacks(emptyMap())
                daddyChannelFallbacks = emptyMap()
                store.clearGuideSchedules()
                lastSync = SupplementSyncSnapshot()
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

    fun schedulePeriodicSpecialEventsMaintenance(
        dlhdBaseProvider: () -> String,
    ) {
        scope.launch {
            delay(30_000)
            while (isActive) {
                if (sportsEnabled()) {
                    val now = System.currentTimeMillis()
                    val plan = EventLifecycleManager.planMaintenanceTick(
                        state = catalogState(),
                        lastSpecialEventsSyncMs = lastSpecialEventsSyncMs,
                        lastVerifyTriggeredSyncMs = lastVerifyTriggeredSyncMs,
                        nowMs = now,
                    )
                    if (!refreshInFlight && specialEventsMutex.tryLock()) {
                        try {
                            applyLifecyclePrune(plan.pruned)
                        } finally {
                            specialEventsMutex.unlock()
                        }
                    }
                    when {
                        plan.shouldRefresh -> {
                            if (plan.mergeWithExisting) {
                                Log.i(
                                    TAG,
                                    "Special Events verify refresh: missingLive=${plan.verify.missingLiveStreams} " +
                                        "upcomingSoon=${plan.verify.upcomingWithoutStreams}",
                                )
                            }
                            val refreshed = refreshSpecialEventsOnly(
                                dlhdScheduleBaseUrl = dlhdBaseProvider(),
                                mergeWithExisting = plan.mergeWithExisting,
                            )
                            if (plan.mergeWithExisting && refreshed) {
                                lastVerifyTriggeredSyncMs = now
                            }
                        }
                        plan.notifyCatalogChanged -> onSpecialEventsChanged?.invoke()
                    }
                }
                delay(SupplementConfig.SPECIAL_EVENTS_PRUNE_INTERVAL_MS)
            }
        }
    }

    fun pruneExpiredSpecialEvents(): Boolean {
        if (!sportsEnabled() || refreshInFlight) return false
        if (!specialEventsMutex.tryLock()) return false
        return try {
            applyLifecyclePrune(EventLifecycleManager.pruneExpired(catalogState()))
        } finally {
            specialEventsMutex.unlock()
        }
    }

    fun scheduleSpecialEventsRefresh(dlhdScheduleBaseUrl: String) {
        if (!sportsEnabled() || refreshInFlight) return
        scope.launch {
            refreshSpecialEventsOnly(dlhdScheduleBaseUrl)
        }
    }

    suspend fun refreshSpecialEventsOnly(
        dlhdScheduleBaseUrl: String?,
        mergeWithExisting: Boolean = false,
    ): Boolean {
        if (!sportsEnabled() || refreshInFlight) return false
        return specialEventsMutex.withLock {
            if (refreshInFlight) return false
            specialEventsRefreshInFlight = true
            try {
                val scheduleBase = dlhdScheduleBaseUrl?.trim()?.trimEnd('/').orEmpty()
                    .ifEmpty { environment.effectiveDlhdBaseUrl().trimEnd('/') }
                val (dlhdStats, bundle) = fetchSpecialEventsBundle(scheduleBase)
                val enriched = enrichSupplementLogos(bundle.channels)
                val eventsScanned = dlhdStats.tvEvents + dlhdStats.tv2Events
                if (mergeWithExisting) {
                    val mergedState = EventLifecycleManager.mergeFetched(
                        existing = catalogState(),
                        fetchedChannels = enriched,
                        fetchedGuideSchedules = bundle.guideProgrammes,
                    )
                    cached = mergedState.channels
                    guideSchedules = mergedState.guideSchedules
                    store.writeChannels(cached)
                    if (mergedState.guideSchedules.isEmpty()) {
                        store.clearGuideSchedules()
                    } else {
                        store.writeGuideSchedules(mergedState.guideSchedules)
                    }
                    rewriteSportsEpg(cached.filter {
                        EventLifecycleManager.isSpecialEventChannel(it.id)
                    }, mergedState.guideSchedules)
                    syncEventMetadata(cached.filter { EventLifecycleManager.isSpecialEventChannel(it.id) })
                } else {
                    val nonSpecial = cached.filter {
                        !EventLifecycleManager.isSpecialEventChannel(it.id)
                    }
                    cached = nonSpecial + enriched
                    store.writeChannels(cached)
                    applySpecialEventsBundle(bundle, eventsScanned)
                }
                lastSpecialEventsSyncMs = System.currentTimeMillis()
                lastSync = lastSync.copy(
                    sportsEventsScanned = eventsScanned,
                    sportsChannels = sportsCount(),
                    specialEventGuides = specialEventGuideCount(),
                    dlhdEventStreams = dlhdEventStreamCount(),
                )
                Log.i(
                    TAG,
                    "Special Events refresh: guides=${specialEventGuideCount()} " +
                        "streams=${dlhdEventStreamCount()} (dlhd scanned=$eventsScanned, " +
                        "merge=$mergeWithExisting)",
                )
                onSpecialEventsChanged?.invoke()
                true
            } catch (exc: Exception) {
                Log.w(TAG, "Special Events refresh failed", exc)
                false
            } finally {
                specialEventsRefreshInFlight = false
            }
        }
    }

    suspend fun refresh(
        daddyChannels: List<Channel>,
        force: Boolean = false,
        dlhdScheduleBaseUrl: String? = null,
    ) {
        if (!enabled()) return
        refreshMutex.withLock {
            if (refreshInFlight) return
            if (!force && !store.isStale() && cached.isNotEmpty()) return
            refreshInFlight = true
        }
        try {
            if (cached.isEmpty()) {
                retainOrRecoverCache(daddyChannels)
            }
            val merged = withTimeoutOrNull(SYNC_MAX_MS) {
                mergeSupplements(daddyChannels, dlhdScheduleBaseUrl)
            }
            if (merged == null) {
                Log.w(TAG, "Supplement sync timed out after ${SYNC_MAX_MS}ms — keeping cache")
                retainOrRecoverCache(daddyChannels)
            } else if (merged.isEmpty()) {
                Log.w(TAG, "Supplement sync returned 0 channels — not overwriting cache")
                retainOrRecoverCache(daddyChannels)
            } else {
                // Publish merged catalog immediately so M3U/health reflect supplements during logo enrich.
                cached = merged
                store.writeChannels(merged)
                applyNameEpgOverrides()
                Log.i(
                    TAG,
                    "Supplement sync: ${merged.size} total " +
                        "(sports=${sportsCount()}, " +
                        "iptv-org=${iptvOrgCount()}, free-tv=${freeTvCount()}, dulo.cx=${duloCxCount()}, ntv.cx=${ntvCxCount()}, " +
                        "adultswim=${adultSwimCount()})",
                )
                // Settle Sources UI before slow logo enrich — catalog is already durable.
                ensureSmartDaddyFallbacks(daddyChannels)
                refreshInFlight = false
                onRefreshComplete?.invoke()
                val enriched = enrichSupplementLogos(merged)
                if (enriched !== merged) {
                    cached = enriched
                    store.writeChannels(enriched)
                }
                return
            }
        } catch (exc: Exception) {
            Log.w(TAG, "Supplement sync failed — keeping cache", exc)
            retainOrRecoverCache(daddyChannels)
        } finally {
            // Always (re)attach Smart merge-style backups from published rows so FULL_CATALOG
            // and timeout/cache-recovery paths still feed /tivimate-smart failover.
            if (refreshInFlight) {
                ensureSmartDaddyFallbacks(daddyChannels)
                refreshInFlight = false
                onRefreshComplete?.invoke()
            }
        }
    }

    fun recoverFromDiskIfNeeded(daddyChannels: List<Channel>): Int {
        if (cached.isNotEmpty()) {
            ensureSmartDaddyFallbacks(daddyChannels)
            return cached.size
        }
        retainOrRecoverCache(daddyChannels)
        ensureSmartDaddyFallbacks(daddyChannels)
        if (cached.isNotEmpty()) {
            onRefreshComplete?.invoke()
        }
        return cached.size
    }

    /** Keep in-memory/disk cache or rebuild NTV.cx rows from [NtvCxCatalogStore] after slow sync. */
    private fun retainOrRecoverCache(daddyChannels: List<Channel>) {
        if (cached.isNotEmpty()) return
        val disk = store.readChannels().filterNot { it.id.startsWith("sup:") }
        if (disk.isNotEmpty()) {
            cached = disk
            Log.i(TAG, "Restored ${disk.size} supplement channels from channels.json")
            return
        }
        val recovered = recoverChannelsFromDiskCaches(daddyChannels)
        if (recovered.isEmpty()) {
            Log.w(TAG, "No supplement channels to recover from disk caches")
            return
        }
        cached = recovered
        store.writeChannels(recovered)
        lastSync = lastSync.copy(
            ntvCxChannels = ntvCxCount(),
            iptvOrgChannels = iptvOrgCount(),
            adultSwimChannels = adultSwimCount(),
            freeTvChannels = freeTvCount(),
            duloCxChannels = duloCxCount(),
            sportsChannels = sportsCount(),
        )
        Log.i(TAG, "Recovered ${recovered.size} supplement channels from disk caches")
    }

    /**
     * Merge fetch-time consolidate maps with score matches against published supplement rows,
     * then apply Channel Backups overrides. Safe to call after cache restore / sync timeout.
     */
    private fun ensureSmartDaddyFallbacks(daddyChannels: List<Channel>) {
        if (daddyChannels.isEmpty() || cached.isEmpty()) return
        val fromPublished = SmartDaddyFallbacksBuilder.fromSupplements(daddyChannels, cached)
        val merged = SupplementFallbackOverridesApplier.apply(
            SupplementImportHelper.mergeDaddyFallbackMaps(daddyChannelFallbacks, fromPublished),
            consolidationOverrides.current(),
        ).mapValues { (_, mirrors) ->
            mirrors.distinctBy { SupplementMatchScorer.mirrorFingerprint(it) }
        }.filterValues { it.isNotEmpty() }
        if (merged.isEmpty() && daddyChannelFallbacks.isEmpty()) return
        daddyChannelFallbacks = merged
        store.writeDaddyFallbacks(merged)
        if (merged.isNotEmpty()) {
            Log.i(
                TAG,
                "Smart daddy fallbacks ready: ${merged.values.sumOf { it.size }} mirrors " +
                    "on ${merged.size} DaddyLive channels",
            )
        }
    }

    private fun recoverChannelsFromDiskCaches(daddyChannels: List<Channel>): List<SupplementChannel> {
        val recovered = SupplementDiskRecovery.recoverNtvFromCatalog(
            enabled = enabled(),
            ntvCxEnabled = ntvCxEnabled(),
            catalogStore = ntvCxCatalogStore,
            daddyChannels = daddyChannels,
            environment = environment,
        )
        if (recovered.daddyFallbacks.isNotEmpty()) {
            val applied = SupplementFallbackOverridesApplier.apply(
                recovered.daddyFallbacks,
                consolidationOverrides.current(),
            )
            daddyChannelFallbacks = applied
            store.writeDaddyFallbacks(applied)
        }
        return recovered.channels
    }

    /**
     * Progressive mid-sync publish: merge ready pieces (retain previous cache for still-loading
     * prefixes) so Free-TV/ntv/dulo appear before iptv-org / Adult Swim finish.
     */
    private fun publishPartialSupplementCatalog(
        previousCached: List<SupplementChannel>,
        specialEvents: List<SupplementChannel>?,
        iptvOrg: List<SupplementChannel>?,
        freeTv: List<SupplementChannel>?,
        duloCx: List<SupplementChannel>?,
        ntvCx: List<SupplementChannel>?,
        adultSwim: List<SupplementChannel>?,
        tmdbVod: List<SupplementChannel>?,
        tmdbVodSeries: List<SupplementChannel>?,
        daddyFallbackMaps: List<Map<String, List<SupplementFallbackMirror>>>,
        syncPatch: SupplementSyncSnapshot,
    ) {
        val merged = SupplementPartialPublisher.mergePieces(
            previousCached = previousCached,
            specialEvents = specialEvents,
            iptvOrg = iptvOrg,
            freeTv = freeTv,
            duloCx = duloCx,
            ntvCx = ntvCx,
            adultSwim = adultSwim,
            tmdbVod = tmdbVod,
            tmdbVodSeries = tmdbVodSeries,
        )
        cached = merged
        store.writeChannels(merged)
        lastSync = syncPatch

        val mergedDaddyFallbacks = SupplementPartialPublisher.mergeDaddyFallbacks(
            daddyFallbackMaps,
            consolidationOverrides,
        )
        if (mergedDaddyFallbacks.isNotEmpty()) {
            daddyChannelFallbacks = mergedDaddyFallbacks
            store.writeDaddyFallbacks(mergedDaddyFallbacks)
        }
    }

    private suspend fun mergeSupplements(
        daddyChannels: List<Channel>,
        dlhdScheduleBaseUrl: String? = null,
    ): List<SupplementChannel> =
        coroutineScope {
            val previousCached = cached
            val publishLock = Mutex()

            var specialEventsReady: List<SupplementChannel>? = if (sportsEnabled()) null else emptyList()
            var iptvReady: List<SupplementChannel>? = if (iptvOrgEnabled()) null else emptyList()
            var freeTvReady: List<SupplementChannel>? = if (freeTvEnabled()) null else emptyList()
            var duloReady: List<SupplementChannel>? = if (duloCxEnabled()) null else emptyList()
            var ntvReady: List<SupplementChannel>? = if (ntvCxEnabled()) null else emptyList()
            var adultSwimReady: List<SupplementChannel>? = if (adultSwimEnabled()) null else emptyList()
            var tmdbVodReady: List<SupplementChannel>? = if (tmdbMoviesEnabled()) null else emptyList()
            var tmdbSeriesReady: List<SupplementChannel>? = if (tmdbMoviesEnabled()) null else emptyList()

            var dlhdEventsScanned = 0
            var specialEventGuides = 0
            var dlhdEventStreams = 0
            var iptvStats = IptvOrgStreamsSource.FetchStats()
            var ntvStats = NtvCxCdnLiveResolver.FetchStats()
            var adultSwimStats = AdultSwimStreamsSource.FetchStats()
            var freeTvStats = FreeTvIptvSource.FetchStats()
            var duloCxStats = DuloCxLiveResolver.FetchStats()

            var iptvFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap()
            var ntvFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap()
            var adultSwimFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap()
            var freeTvFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap()
            var duloFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap()

            fun currentSyncSnapshot(
                specialEvents: List<SupplementChannel>?,
                iptvOrg: List<SupplementChannel>?,
                freeTv: List<SupplementChannel>?,
                duloCx: List<SupplementChannel>?,
                ntvCx: List<SupplementChannel>?,
                adultSwim: List<SupplementChannel>?,
                tmdbVod: List<SupplementChannel>?,
                tmdbVodSeries: List<SupplementChannel>?,
            ): SupplementSyncSnapshot {
                val relay = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status()
                return SupplementSyncSnapshot(
                    sportsChannels = specialEvents?.size ?: lastSync.sportsChannels,
                    specialEventGuides = specialEventGuides.takeIf { specialEvents != null }
                        ?: lastSync.specialEventGuides,
                    dlhdEventStreams = dlhdEventStreams.takeIf { specialEvents != null }
                        ?: lastSync.dlhdEventStreams,
                    sportsEventsScanned = dlhdEventsScanned.takeIf { specialEvents != null }
                        ?: lastSync.sportsEventsScanned,
                    iptvOrgChannels = iptvOrg?.size ?: lastSync.iptvOrgChannels,
                    iptvOrgPlaylistsFetched = iptvStats.playlistsFetched.takeIf { iptvOrg != null }
                        ?: lastSync.iptvOrgPlaylistsFetched,
                    iptvOrgPlaylistsFailed = iptvStats.playlistsFailed.takeIf { iptvOrg != null }
                        ?: lastSync.iptvOrgPlaylistsFailed,
                    iptvOrgPlaylistsTotal = iptvStats.playlistsTotal.takeIf { it > 0 || iptvOrg != null }
                        ?: lastSync.iptvOrgPlaylistsTotal,
                    iptvOrgPlaylistsFromCache = iptvStats.playlistsFromCache.takeIf { iptvOrg != null }
                        ?: lastSync.iptvOrgPlaylistsFromCache,
                    iptvOrgEntriesParsed = iptvStats.entriesParsed.takeIf { iptvOrg != null }
                        ?: lastSync.iptvOrgEntriesParsed,
                    ntvCxChannels = ntvCx?.size ?: lastSync.ntvCxChannels,
                    ntvCxResolveProbeOk = if (ntvCx != null) ntvStats.resolveProbeOk else lastSync.ntvCxResolveProbeOk,
                    adultSwimChannels = adultSwim?.size ?: lastSync.adultSwimChannels,
                    adultSwimProbed = if (adultSwim != null) adultSwimStats.probed else lastSync.adultSwimProbed,
                    adultSwimProbeOk = if (adultSwim != null) adultSwimStats.probeOk else lastSync.adultSwimProbeOk,
                    freeTvChannels = freeTv?.size ?: lastSync.freeTvChannels,
                    freeTvPlaylistsFetched = if (freeTv != null) {
                        freeTvStats.playlistsFetched
                    } else {
                        lastSync.freeTvPlaylistsFetched
                    },
                    freeTvPlaylistsFailed = if (freeTv != null) {
                        freeTvStats.playlistsFailed
                    } else {
                        lastSync.freeTvPlaylistsFailed
                    },
                    duloCxChannels = duloCx?.size ?: lastSync.duloCxChannels,
                    duloCxCatalogFetchOk = if (duloCx != null) {
                        duloCxStats.catalogFetchOk
                    } else {
                        lastSync.duloCxCatalogFetchOk
                    },
                    duloCxResolveProbeOk = if (duloCx != null) {
                        duloCxStats.resolveProbeOk
                    } else {
                        lastSync.duloCxResolveProbeOk
                    },
                    duloCxAuthConfigured = if (duloCx != null) {
                        duloCxStats.authConfigured
                    } else {
                        lastSync.duloCxAuthConfigured
                    },
                    tmdbVodMovies = tmdbVod?.size ?: lastSync.tmdbVodMovies,
                    tmdbVodSeries = tmdbVodSeries?.size ?: lastSync.tmdbVodSeries,
                    vodCatalogRelayActive = relay.active,
                    vodCatalogRelayVersion = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.version,
                    vodCatalogRelayMovies = relay.movies,
                    vodCatalogRelayShows = relay.shows,
                    vodCatalogRelayProbed = relay.probed,
                    vodCatalogRelayProbeOk = relay.probeOk,
                    vodCatalogRelayDeadPruned = relay.deadPruned,
                )
            }

            suspend fun publishProgress() {
                publishLock.withLock {
                    publishPartialSupplementCatalog(
                        previousCached = previousCached,
                        specialEvents = specialEventsReady,
                        iptvOrg = iptvReady,
                        freeTv = freeTvReady,
                        duloCx = duloReady,
                        ntvCx = ntvReady,
                        adultSwim = adultSwimReady,
                        tmdbVod = tmdbVodReady,
                        tmdbVodSeries = tmdbSeriesReady,
                        daddyFallbackMaps = listOf(
                            iptvFallbacks,
                            ntvFallbacks,
                            adultSwimFallbacks,
                            freeTvFallbacks,
                            duloFallbacks,
                        ),
                        syncPatch = currentSyncSnapshot(
                            specialEventsReady,
                            iptvReady,
                            freeTvReady,
                            duloReady,
                            ntvReady,
                            adultSwimReady,
                            tmdbVodReady,
                            tmdbSeriesReady,
                        ),
                    )
                }
            }

            // FAST EPG: load disk index immediately; stagger network refresh behind iptv-org
            // playlist wave so GitHub raw + mjh feeds do not stampede DNS together.
            val fastEpgDeferred = async {
                if (environment.gatewayEpgEnabled && iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
                    runCatching {
                        fastEpgCatalog.loadIndexFromDisk()
                        val staleOrEmpty =
                            fastEpgCatalog.isStale() || fastEpgCatalog.cachedFeedFiles().isEmpty()
                        if (staleOrEmpty) {
                            delay(8_000L)
                        }
                        fastEpgCatalog.refreshAsync(force = staleOrEmpty)
                    }.onFailure { exc -> Log.w(TAG, "FAST EPG refresh failed", exc) }
                }
            }

            val specialEventsDeferred = async {
                if (!sportsEnabled()) {
                    specialEventsReady = emptyList()
                    return@async emptyList<SupplementChannel>()
                }
                val scheduleBase = dlhdScheduleBaseUrl?.trim()?.trimEnd('/').orEmpty()
                    .ifEmpty { environment.effectiveDlhdBaseUrl().trimEnd('/') }
                val (dlhdStats, bundle) = fetchSpecialEventsBundle(scheduleBase)
                dlhdEventsScanned = dlhdStats.tvEvents + dlhdStats.tv2Events
                applySpecialEventsBundle(bundle, dlhdEventsScanned)
                specialEventGuides = bundle.channels.count { it.id.startsWith("dlhd-guide:") }
                dlhdEventStreams = bundle.channels.count { it.id.startsWith("dlhd-event:") }
                specialEventsReady = bundle.channels
                publishProgress()
                bundle.channels
            }

            val iptvOrgDeferred = async {
                if (!iptvOrgEnabled()) {
                    iptvReady = emptyList()
                    return@async IptvOrgStreamsSource.FetchOutcome(
                        emptyList(),
                        IptvOrgStreamsSource.FetchStats(),
                    )
                }
                runCatching {
                    iptvOrgSource.fetchChannels(
                        daddyChannels,
                        environment.supplementIptvOrgImportMode,
                        environment.iptvOrgEnabledPlaylists,
                        onProgress = { outcome ->
                            publishLock.withLock {
                                iptvStats = outcome.stats
                                iptvReady = outcome.channels
                                iptvFallbacks = outcome.daddyFallbacks
                                publishPartialSupplementCatalog(
                                    previousCached = previousCached,
                                    specialEvents = specialEventsReady,
                                    iptvOrg = iptvReady,
                                    freeTv = freeTvReady,
                                    duloCx = duloReady,
                                    ntvCx = ntvReady,
                                    adultSwim = adultSwimReady,
                                    tmdbVod = tmdbVodReady,
                                    tmdbVodSeries = tmdbSeriesReady,
                                    daddyFallbackMaps = listOf(
                                        iptvFallbacks,
                                        ntvFallbacks,
                                        adultSwimFallbacks,
                                        freeTvFallbacks,
                                        duloFallbacks,
                                    ),
                                    syncPatch = currentSyncSnapshot(
                                        specialEventsReady,
                                        iptvReady,
                                        freeTvReady,
                                        duloReady,
                                        ntvReady,
                                        adultSwimReady,
                                        tmdbVodReady,
                                        tmdbSeriesReady,
                                    ),
                                )
                            }
                        },
                    )
                }.getOrElse { exc ->
                    Log.w(TAG, "iptv-org fetch failed", exc)
                    IptvOrgStreamsSource.FetchOutcome(emptyList(), IptvOrgStreamsSource.FetchStats())
                }.also { outcome ->
                    iptvStats = outcome.stats
                    iptvFallbacks = outcome.daddyFallbacks
                    iptvReady = outcome.channels
                    publishProgress()
                }
            }

            val ntvCxDeferred = async {
                if (!ntvCxEnabled()) {
                    ntvReady = emptyList()
                    return@async NtvCxCdnLiveSource.FetchOutcome(
                        emptyList(),
                        NtvCxCdnLiveResolver.FetchStats(),
                    )
                }
                runCatching {
                    ntvCxSource.fetchChannels(
                        daddyChannels,
                        environment.supplementNtvCxImportMode,
                        nameIndex,
                    )
                }.getOrElse { exc ->
                    Log.w(TAG, "ntv.cx 24/7 fetch failed", exc)
                    NtvCxCdnLiveSource.FetchOutcome(emptyList(), NtvCxCdnLiveResolver.FetchStats())
                }.also { outcome ->
                    var channels = outcome.channels
                    if (channels.isEmpty()) {
                        val cachedNtv = previousCached.filter { it.id.startsWith("ntv:") }
                        if (cachedNtv.isNotEmpty()) {
                            Log.w(TAG, "ntv.cx fetch empty — keeping ${cachedNtv.size} cached channels")
                            channels = cachedNtv
                        }
                    }
                    ntvStats = outcome.stats
                    ntvFallbacks = outcome.daddyFallbacks
                    ntvReady = channels
                    publishProgress()
                }
            }

            val hasAdultSwimCache = previousCached.any { it.id.startsWith("adultswim:") }
            val adultSwimDeferred = async {
                if (!adultSwimEnabled()) {
                    adultSwimReady = emptyList()
                    return@async AdultSwimStreamsSource.FetchOutcome(
                        emptyList(),
                        AdultSwimStreamsSource.FetchStats(),
                    )
                }
                val outcome = withTimeoutOrNull(AdultSwimStreamsConfig.PROBE_BUDGET_MS + 3_000L) {
                    runCatching {
                        adultSwimSource.fetchChannels(
                            daddyChannels,
                            environment.supplementAdultSwimImportMode,
                            probeBudgetMs = AdultSwimStreamsConfig.PROBE_BUDGET_MS,
                            preferCachedOnBudgetExceed = hasAdultSwimCache,
                        )
                    }.getOrElse { emptyAdultSwimOutcome(it) }
                } ?: run {
                    Log.w(TAG, "adult swim sync slot timed out — prefer cache / empty")
                    AdultSwimStreamsSource.FetchOutcome(
                        emptyList(),
                        AdultSwimStreamsSource.FetchStats(
                            catalogRows = AdultSwimStreamsConfig.CATALOG.size,
                            probeBudgetExceeded = true,
                        ),
                    )
                }
                var channels = outcome.channels
                if (channels.isEmpty()) {
                    val cachedAdultSwim = previousCached.filter { it.id.startsWith("adultswim:") }
                    if (cachedAdultSwim.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "adult swim probe empty — keeping ${cachedAdultSwim.size} cached channels",
                        )
                        channels = cachedAdultSwim
                    }
                }
                adultSwimStats = outcome.stats
                adultSwimFallbacks = outcome.daddyFallbacks
                adultSwimReady = channels
                publishProgress()
                outcome.copy(channels = channels)
            }

            val freeTvDeferred = async {
                if (!freeTvEnabled()) {
                    freeTvReady = emptyList()
                    return@async FreeTvIptvSource.FetchOutcome(emptyList(), FreeTvIptvSource.FetchStats())
                }
                val outcome = withTimeoutOrNull(FreeTvIptvConfig.FETCH_BUDGET_MS) {
                    runCatching {
                        freeTvSource.fetchChannels(
                            daddyChannels,
                            environment.supplementFreeTvImportMode,
                        )
                    }.getOrElse { emptyFreeTvOutcome(it) }
                } ?: run {
                    Log.w(TAG, "Free-TV sync slot timed out — prefer cache / empty")
                    FreeTvIptvSource.FetchOutcome(
                        emptyList(),
                        FreeTvIptvSource.FetchStats(
                            playlistsFailed = FreeTvIptvConfig.PLAYLIST_FILES.size,
                        ),
                    )
                }
                var channels = outcome.channels
                if (channels.isEmpty()) {
                    val cachedFreeTv =
                        previousCached.filter { it.id.startsWith(FreeTvIptvConfig.ID_PREFIX) }
                    if (cachedFreeTv.isNotEmpty()) {
                        Log.w(TAG, "Free-TV fetch empty — keeping ${cachedFreeTv.size} cached channels")
                        channels = cachedFreeTv
                    }
                }
                freeTvStats = outcome.stats
                freeTvFallbacks = outcome.daddyFallbacks
                freeTvReady = channels
                publishProgress()
                outcome.copy(channels = channels)
            }

            val duloCxDeferred = async {
                if (!duloCxEnabled()) {
                    duloReady = emptyList()
                    return@async DuloCxLiveSource.FetchOutcome(
                        emptyList(),
                        DuloCxLiveResolver.FetchStats(),
                    )
                }
                val outcome = withTimeoutOrNull(DuloCxLiveConfig.CATALOG_FETCH_BUDGET_MS) {
                    runCatching {
                        duloCxSource.fetchChannels(
                            daddyChannels = daddyChannels,
                            importMode = environment.supplementDuloCxImportMode,
                            nameIndex = nameIndex,
                            authConfigured = environment.supplementDuloCxAccessToken.isNotBlank(),
                        )
                    }.getOrElse { emptyDuloOutcome(it) }
                } ?: run {
                    Log.w(TAG, "dulo.cx sync slot timed out — prefer cache / empty")
                    DuloCxLiveSource.FetchOutcome(
                        emptyList(),
                        DuloCxLiveResolver.FetchStats(
                            authConfigured = environment.supplementDuloCxAccessToken.isNotBlank(),
                        ),
                    )
                }
                var channels = outcome.channels
                if (channels.isEmpty()) {
                    val cachedDulo =
                        previousCached.filter { it.id.startsWith(DuloCxLiveConfig.ID_PREFIX) }
                    if (cachedDulo.isNotEmpty()) {
                        Log.w(TAG, "dulo.cx fetch empty — keeping ${cachedDulo.size} cached channels")
                        channels = cachedDulo
                    }
                }
                duloCxStats = outcome.stats
                duloFallbacks = outcome.daddyFallbacks
                duloReady = channels
                publishProgress()
                outcome.copy(channels = channels)
            }

            val tmdbVodDeferred = async {
                if (!tmdbMoviesEnabled()) {
                    tmdbVodReady = emptyList()
                    return@async emptyList<SupplementChannel>() to TmdbVodSource.FetchStats()
                }
                if (environment.vodCatalogRelayEnabled) {
                    runCatching {
                        val app = appContext as? com.thothassistant.stepdaddy.gateway.GatewayApp
                        app?.vodCatalogRelayManager?.refresh(reason = "vod-sync", probeStreams = false)
                    }
                    scope.launch {
                        runCatching {
                            val app = appContext as? com.thothassistant.stepdaddy.gateway.GatewayApp
                            app?.vodCatalogRelayManager?.refresh(
                                reason = "vod-sync-probe",
                                probeStreams = true,
                            )
                        }.onFailure { exc ->
                            Log.w(TAG, "VOD stream probe (background) failed", exc)
                        }
                    }
                }
                runCatching { tmdbVodSource.fetchChannels() }
                    .getOrElse { exc ->
                        Log.w(TAG, "TMDB VOD fetch failed", exc)
                        emptyList<SupplementChannel>() to TmdbVodSource.FetchStats()
                    }.also { (channels, _) ->
                        var published = channels
                        if (published.isEmpty()) {
                            val cachedVod =
                                previousCached.filter { it.id.startsWith(TmdbVodConfig.ID_PREFIX) }
                            if (cachedVod.isNotEmpty()) {
                                Log.w(TAG, "TMDB VOD fetch empty — keeping ${cachedVod.size} cached movies")
                                published = cachedVod
                            }
                        }
                        tmdbVodReady = published
                        publishProgress()
                    }
            }

            val tmdbVodSeriesDeferred = async {
                if (!tmdbMoviesEnabled()) {
                    tmdbSeriesReady = emptyList()
                    return@async emptyList<SupplementChannel>() to TmdbVodSeriesSource.FetchStats()
                }
                runCatching { tmdbVodSeriesSource.fetchChannels() }
                    .getOrElse { exc ->
                        Log.w(TAG, "series VOD fetch failed", exc)
                        emptyList<SupplementChannel>() to TmdbVodSeriesSource.FetchStats()
                    }.also { (channels, _) ->
                        var published = channels
                        if (published.isEmpty()) {
                            val cachedSeries =
                                previousCached.filter { it.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) }
                            if (cachedSeries.isNotEmpty()) {
                                Log.w(
                                    TAG,
                                    "series VOD fetch empty — keeping ${cachedSeries.size} cached episodes",
                                )
                                published = cachedSeries
                            }
                        }
                        tmdbSeriesReady = published
                        publishProgress()
                    }
            }

            val specialEvents = specialEventsDeferred.await()
            iptvOrgDeferred.await()
            // Re-backfill tvg-ids if FAST refresh finished alongside iptv (progressive catalog).
            if (fastEpgDeferred.isCompleted) {
                backfillFastTvgIdsFromCatalog()
            }
            ntvCxDeferred.await()
            adultSwimDeferred.await()
            freeTvDeferred.await()
            duloCxDeferred.await()
            tmdbVodDeferred.await()
            tmdbVodSeriesDeferred.await()

            // Finish FAST refresh with a hard ceiling — GitHub/mjh stalls must not hold
            // supplementSyncInFlight forever (Sources UI / progress depends on it clearing).
            withTimeoutOrNull(90_000L) {
                runCatching { fastEpgDeferred.await() }
            } ?: Log.w(TAG, "FAST EPG await timed out — continuing sync finalize")

            if (environment.gatewayEpgEnabled && iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
                withTimeoutOrNull(60_000L) {
                    runCatching {
                        iptvOrgEpgRepository.refresh(
                            environment.iptvOrgEpgUrl.takeIf { it.isNotBlank() },
                        )
                    }.onFailure { exc -> Log.w(TAG, "iptv-org EPG refresh failed", exc) }
                } ?: Log.w(TAG, "iptv-org EPG refresh timed out — continuing")
            }

            val mergedDaddyFallbacks = SupplementFallbackOverridesApplier.apply(
                SupplementImportHelper.mergeDaddyFallbackMaps(
                    iptvFallbacks,
                    ntvFallbacks,
                    adultSwimFallbacks,
                    freeTvFallbacks,
                    duloFallbacks,
                ),
                consolidationOverrides.current(),
            )
            daddyChannelFallbacks = mergedDaddyFallbacks
            store.writeDaddyFallbacks(mergedDaddyFallbacks)
            if (mergedDaddyFallbacks.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Consolidated ${mergedDaddyFallbacks.values.sumOf { it.size }} supplement fallbacks " +
                        "onto ${mergedDaddyFallbacks.size} DaddyLive channels",
                )
            }

            val iptvOrg = iptvReady.orEmpty()
            val freeTv = freeTvReady.orEmpty()
            val duloCx = duloReady.orEmpty()
            val ntvCx = ntvReady.orEmpty()
            val adultSwim = adultSwimReady.orEmpty()
            val publishedTmdbVod = tmdbVodReady.orEmpty()
            val publishedTmdbVodSeries = tmdbSeriesReady.orEmpty()

            lastSync = SupplementSyncSnapshot(
                sportsChannels = specialEvents.size,
                specialEventGuides = specialEventGuides,
                dlhdEventStreams = dlhdEventStreams,
                sportsEventsScanned = dlhdEventsScanned,
                iptvOrgChannels = iptvOrg.size,
                iptvOrgPlaylistsFetched = iptvStats.playlistsFetched,
                iptvOrgPlaylistsFailed = iptvStats.playlistsFailed,
                iptvOrgPlaylistsTotal = iptvStats.playlistsTotal,
                iptvOrgPlaylistsFromCache = iptvStats.playlistsFromCache,
                iptvOrgEntriesParsed = iptvStats.entriesParsed,
                ntvCxChannels = ntvCx.size,
                ntvCxResolveProbeOk = ntvStats.resolveProbeOk,
                adultSwimChannels = adultSwim.size,
                adultSwimProbed = adultSwimStats.probed,
                adultSwimProbeOk = adultSwimStats.probeOk,
                freeTvChannels = freeTv.size,
                freeTvPlaylistsFetched = freeTvStats.playlistsFetched,
                freeTvPlaylistsFailed = freeTvStats.playlistsFailed,
                duloCxChannels = duloCx.size,
                duloCxCatalogFetchOk = duloCxStats.catalogFetchOk,
                duloCxResolveProbeOk = duloCxStats.resolveProbeOk,
                duloCxAuthConfigured = duloCxStats.authConfigured,
                tmdbVodMovies = publishedTmdbVod.size,
                tmdbVodSeries = publishedTmdbVodSeries.size,
                vodCatalogRelayActive = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().active,
                vodCatalogRelayVersion = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.version,
                vodCatalogRelayMovies = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().movies,
                vodCatalogRelayShows = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().shows,
                vodCatalogRelayProbed = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().probed,
                vodCatalogRelayProbeOk = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().probeOk,
                vodCatalogRelayDeadPruned = com.thothassistant.stepdaddy.gateway.relay.VodCatalogRelayRuntime.status().deadPruned,
            )

            val merged =
                specialEvents + iptvOrg + freeTv + duloCx + ntvCx + adultSwim +
                    publishedTmdbVod + publishedTmdbVodSeries
            cached = merged
            store.writeChannels(merged)
            // Backfill after final catalog write so refresh() keeps corrected tvg-ids.
            backfillFastTvgIdsFromCatalog()
            cached
        }

    private fun fetchSpecialEventsBundle(
        scheduleBase: String,
    ): Pair<DaddyLiveEventResolver.ResolveStats, SpecialEventsMerger.EpgBundle> =
        SupplementSpecialEventsSupport.fetchBundle(scheduleBase, environment, dlhdEventResolver)

    private fun applySpecialEventsBundle(
        bundle: SpecialEventsMerger.EpgBundle,
        eventsScanned: Int,
    ) {
        guideSchedules = bundle.guideProgrammes.mapValues { (_, rows) -> rows.toList() }
        if (bundle.channels.isNotEmpty()) {
            store.writeGuideSchedules(guideSchedules)
        } else {
            guideSchedules = emptyMap()
            store.clearGuideSchedules()
        }
        rewriteSportsEpg(bundle.channels, bundle.guideProgrammes)
        lastSpecialEventsSyncMs = System.currentTimeMillis()
        lastSync = lastSync.copy(
            sportsEventsScanned = eventsScanned,
            sportsChannels = bundle.channels.size,
            specialEventGuides = bundle.channels.count { it.id.startsWith("dlhd-guide:") },
            dlhdEventStreams = bundle.channels.count { it.id.startsWith("dlhd-event:") },
        )
        syncEventMetadata(bundle.channels)
    }

    private fun syncEventMetadata(channels: List<SupplementChannel>) {
        if (channels.isEmpty()) {
            eventMetadata = emptyMap()
            eventMetadataStore.clear()
            return
        }
        eventMetadata = EventMetadataScraper.scrapeChannels(channels).also {
            eventMetadataStore.writeAll(it)
            Log.d(TAG, "Event metadata synced: ${it.size} entries")
        }
    }

    private fun retainEventMetadataForChannels(channels: List<SupplementChannel>) {
        val ids = channels
            .filter { EventLifecycleManager.isSpecialEventChannel(it.id) }
            .map { it.id }
            .toSet()
        eventMetadataStore.retainOnly(ids)
        eventMetadata = eventMetadata.filterKeys { it in ids }
    }

    private fun catalogState(): EventLifecycleManager.CatalogState =
        EventLifecycleManager.CatalogState(channels = cached, guideSchedules = guideSchedules)

    private fun applyLifecyclePrune(outcome: EventLifecycleManager.PruneOutcome): Boolean {
        if (!outcome.changed) return false
        cached = outcome.state.channels
        guideSchedules = outcome.state.guideSchedules
        store.writeChannels(cached)
        if (outcome.state.guideSchedules.isEmpty()) store.clearGuideSchedules()
        else store.writeGuideSchedules(outcome.state.guideSchedules)
        rewriteSportsEpg()
        retainEventMetadataForChannels(cached)
        lastSync = lastSync.copy(
            sportsChannels = sportsCount(),
            specialEventGuides = specialEventGuideCount(),
            dlhdEventStreams = dlhdEventStreamCount(),
        )
        Log.i(
            TAG,
            "Special Events prune: -${outcome.removedStreams} streams, " +
                "-${outcome.removedGuides} guides, -${outcome.removedScheduleRows} schedule rows, " +
                "-${outcome.dedupeRemoved} deduped",
        )
        return true
    }

    private fun rewriteSportsEpg(
        channels: List<SupplementChannel> = cached.filter {
            EventLifecycleManager.isSpecialEventChannel(it.id)
        },
        programmes: Map<String, List<SpecialEventsMerger.GuideEventRow>> = guideSchedules,
    ) = SupplementSpecialEventsSupport.rewriteSportsEpg(store.sportsEpgFile, channels, programmes)

    private fun resolveDlhdScheduleEvents(primaryBase: String) =
        SupplementSpecialEventsSupport.resolveDlhdScheduleEvents(
            primaryBase = primaryBase,
            environment = environment,
            dlhdEventResolver = dlhdEventResolver,
        )

    companion object {
        private const val TAG = "SupplementSource"
        /** iptv-org fetches 39 playlists + FAST EPG on slow STBs (e.g. MiTV) can exceed 2 min. */
        /** Hard ceiling for one supplement merge; GitHub/CDN stalls must not freeze Sources UI. */
        private const val SYNC_MAX_MS = 360_000L

        private fun emptyAdultSwimOutcome(exc: Throwable): AdultSwimStreamsSource.FetchOutcome {
            Log.w(TAG, "adult swim fetch failed", exc)
            return AdultSwimStreamsSource.FetchOutcome(
                emptyList(),
                AdultSwimStreamsSource.FetchStats(),
            )
        }

        private fun emptyFreeTvOutcome(exc: Throwable): FreeTvIptvSource.FetchOutcome {
            Log.w(TAG, "Free-TV fetch failed", exc)
            return FreeTvIptvSource.FetchOutcome(emptyList(), FreeTvIptvSource.FetchStats())
        }

        private fun emptyDuloOutcome(exc: Throwable): DuloCxLiveSource.FetchOutcome {
            Log.w(TAG, "dulo.cx live fetch failed", exc)
            return DuloCxLiveSource.FetchOutcome(emptyList(), DuloCxLiveResolver.FetchStats())
        }
    }
}
