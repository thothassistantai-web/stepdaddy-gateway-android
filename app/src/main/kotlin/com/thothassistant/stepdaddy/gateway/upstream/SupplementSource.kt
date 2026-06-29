package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.FastChannelTvgIdResolver
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.SpecialEventsEpgGenerator
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.EventMetadata
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class SupplementSource(
    context: Context,
    private val environment: GatewayEnvironment,
    private val nameIndex: IptvOrgNameIndex = IptvOrgNameIndex(context),
    private val epgChannelMapper: EpgChannelMapper? = null,
    private val logoResolver: LogoResolver? = null,
    private val channelMetaStore: ChannelMetaStore? = null,
    private val httpClient: OkHttpClient = defaultClient(),
    private val sportsResolver: TheTvAppSportsResolver = TheTvAppSportsResolver(httpClient),
    private val iptvOrgEpgRepository: IptvOrgEpgRepository = IptvOrgEpgRepository(context, httpClient),
) {
    private val fastEpgCatalog = FastEpgCatalog(context)
    private val fastChannelTvgIdResolver = FastChannelTvgIdResolver(fastEpgCatalog, epgChannelMapper)
    private val iptvOrgSource = IptvOrgStreamsSource(
        context,
        httpClient,
        fastEpgCatalog = fastEpgCatalog,
        fastChannelTvgIdResolver = fastChannelTvgIdResolver,
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
    private val dlhdEventResolver = DaddyLiveEventResolver(httpClient)
    private val dlhdEventHealthStore = DlhdEventStreamHealthStore()
    private val dlhdEventProber = DlhdEventStreamProber(httpClient = httpClient)
    private val eventStreamHealthMonitor by lazy {
        EventStreamHealthMonitor(
            channelProvider = { cached.filter { it.id.startsWith("dlhd-event:") } },
            store = dlhdEventHealthStore,
            prober = dlhdEventProber,
            sportsEnabled = { sportsEnabled() },
            onStatesChanged = { onDlhdEventHealthChanged?.invoke() },
        )
    }

    data class SyncSnapshot(
        val blockedTheTvApp: Int = 0,
        val blockedTvPass: Int = 0,
        val blockedTokenProxy: Int = 0,
        val sportsChannels: Int = 0,
        val specialEventGuides: Int = 0,
        val dlhdEventStreams: Int = 0,
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
    private val eventMetadataStore = EventMetadataStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val specialEventsMutex = Mutex()
    @Volatile
    private var refreshInFlight = false
    @Volatile
    private var lastSpecialEventsSyncMs = 0L
    @Volatile
    private var lastVerifyTriggeredSyncMs = 0L
    @Volatile
    private var cached: List<SupplementChannel> = store.readChannels().filterNot { it.id.startsWith("sup:") }
    @Volatile
    private var guideSchedules: Map<String, List<SpecialEventsMerger.GuideEventRow>> = store.readGuideSchedules()
    @Volatile
    private var lastSync = SyncSnapshot()
    @Volatile
    private var eventMetadata: Map<String, EventMetadata> = eventMetadataStore.readAll()

    init {
        if (sportsEnabled()) {
            pruneExpiredSpecialEvents()
        }
        if (sportsCount() > 0) {
            lastSpecialEventsSyncMs = store.guideSchedulesSyncedAtMs()
                .takeIf { it > 0L }
                ?: store.lastSyncedAtMs()
            if (eventMetadata.isEmpty()) {
                syncEventMetadata(cached.filter { EventLifecycleManager.isSpecialEventChannel(it.id) })
            }
        }
    }

    @Volatile
    var onRefreshComplete: (() -> Unit)? = null

    @Volatile
    var onSpecialEventsChanged: (() -> Unit)? = null

    fun enabled(): Boolean =
        environment.supplementSportsEnabled ||
            environment.supplementIptvOrgEnabled ||
            environment.supplementNtvCxEnabled ||
            environment.supplementAdultSwimEnabled

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

    fun sportsCount(): Int = cached.count {
        it.id.startsWith("sport:") ||
            it.id.startsWith("dlhd-guide:") ||
            it.id.startsWith("dlhd-event:")
    }

    fun specialEventGuideCount(): Int = cached.count { it.id.startsWith("dlhd-guide:") }

    fun dlhdEventStreamCount(): Int = cached.count { it.id.startsWith("dlhd-event:") }

    fun iptvOrgCount(): Int = cached.count { it.id.startsWith("iptv:") }

    fun ntvCxCount(): Int = cached.count { it.id.startsWith("ntv:") }

    fun adultSwimCount(): Int = cached.count { it.id.startsWith("adultswim:") }

    fun ntvChannel(token: String): SupplementChannel? =
        cached.firstOrNull { it.id == "ntv:$token" }

    fun syncSnapshot(): SyncSnapshot = lastSync

    fun syncInFlight(): Boolean = refreshInFlight

    fun specialEventsLastSyncMs(): Long = lastSpecialEventsSyncMs.takeIf { it > 0L } ?: 0L

    fun epgXmlFile(): File? = store.epgFile()

    fun fastEpgFeedFiles(): List<File> {
        if (!environment.iptvOrgEpgEnabled || !iptvOrgEnabled()) return emptyList()
        return fastEpgCatalog.cachedFeedFiles()
    }

    /** Download FAST provider guides if missing; backfill mjh hash tvg-ids on cached iptv-org rows. */
    fun prepareFastEpgForBuild(): Int {
        applyNameEpgOverrides()
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
            it.id.startsWith("sport:") ||
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

    fun dlhdEventStreamHealth(token: String): DlhdEventStreamHealth.Status =
        dlhdEventHealthStore.status(token)

    fun isDlhdEventStreamHealthy(token: String): Boolean =
        dlhdEventHealthStore.isHealthy(token)

    fun dlhdEventStreamHealthSummary(): DlhdEventStreamHealth.Summary =
        dlhdEventHealthStore.summary(dlhdEventStreamCount())

    fun recordDlhdEventStreamHealth(token: String, result: DlhdEventStreamHealth.ProbeResult) {
        val before = dlhdEventHealthStore.revision()
        dlhdEventHealthStore.record(token, result)
        if (dlhdEventHealthStore.revision() != before) {
            onDlhdEventHealthChanged?.invoke()
        }
    }

    fun dlhdEventHealthStore(): DlhdEventStreamHealthStore = dlhdEventHealthStore

    fun eventStreamHealthMonitor(): EventStreamHealthMonitor = eventStreamHealthMonitor

    var onDlhdEventHealthChanged: (() -> Unit)? = null

    fun scheduleDlhdEventStreamHealthProbes(
        tvStreamProbe: suspend (channelId: String) -> Boolean,
    ) {
        eventStreamHealthMonitor.start(tvStreamProbe)
    }

    fun stopDlhdEventStreamHealthProbes() {
        eventStreamHealthMonitor.stop()
    }

    fun dlhdGuideChannel(slug: String): SupplementChannel? {
        val normalized = slug.trim().trim('/')
        if (normalized.isEmpty()) return null
        return cached.firstOrNull {
            it.id == "dlhd-guide:$normalized" || it.id.removePrefix("dlhd-guide:") == normalized
        }
    }

    fun eventMetadata(channelId: String): EventMetadata? = eventMetadata[channelId.trim()]

    fun eventMetadataMap(): Map<String, EventMetadata> = eventMetadata

    fun eventMetadataCount(): Int = eventMetadata.size

    fun guideSchedule(guideId: String): List<SpecialEventsMerger.GuideEventRow> =
        guideSchedules[guideId].orEmpty()

    fun guideScheduleContentKey(guideId: String): String =
        GuideScheduleMediaCache.contentKey(
            events = guideSchedule(guideId),
            syncedAtMs = store.lastSyncedAtMs(),
        )

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
                store.clearGuideSchedules()
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
            try {
                val scheduleBase = dlhdScheduleBaseUrl?.trim()?.trimEnd('/').orEmpty()
                    .ifEmpty { environment.dlhdBaseUrl.trimEnd('/') }
                val (tvAppStats, bundle) = fetchSpecialEventsBundle(scheduleBase)
                val enriched = enrichSupplementLogos(bundle.channels)
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
                    applySpecialEventsBundle(bundle, tvAppStats.eventsScanned)
                }
                lastSpecialEventsSyncMs = System.currentTimeMillis()
                lastSync = lastSync.copy(
                    sportsEventsScanned = tvAppStats.eventsScanned,
                    sportsChannels = sportsCount(),
                    specialEventGuides = specialEventGuideCount(),
                    dlhdEventStreams = dlhdEventStreamCount(),
                )
                Log.i(
                    TAG,
                    "Special Events refresh: guides=${specialEventGuideCount()} " +
                        "streams=${dlhdEventStreamCount()} (tvApp scanned=${tvAppStats.eventsScanned}, " +
                        "merge=${mergeWithExisting})",
                )
                onSpecialEventsChanged?.invoke()
                true
            } catch (exc: Exception) {
                Log.w(TAG, "Special Events refresh failed", exc)
                false
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
            if (cached.isEmpty()) {
                retainOrRecoverCache(daddyChannels)
            }
            coroutineScope {
                val mergeJob = async(Dispatchers.IO) {
                    mergeSupplements(daddyChannels, dlhdScheduleBaseUrl)
                }
                try {
                    val merged = withTimeoutOrNull(SYNC_MAX_MS) { mergeJob.await() }
                    if (merged == null) {
                        mergeJob.cancel()
                        Log.w(TAG, "Supplement sync timed out after ${SYNC_MAX_MS}ms — keeping cache")
                        retainOrRecoverCache(daddyChannels)
                        onRefreshComplete?.invoke()
                        return@coroutineScope
                    }
                    val supplements = enrichSupplementLogos(merged)
                    if (supplements.isEmpty()) {
                        Log.w(TAG, "Supplement sync returned 0 channels — not overwriting cache")
                        retainOrRecoverCache(daddyChannels)
                        onRefreshComplete?.invoke()
                        return@coroutineScope
                    }
                    cached = supplements
                    store.writeChannels(supplements)
                    applyNameEpgOverrides()
                    Log.i(
                        TAG,
                        "Supplement sync: ${supplements.size} total " +
                            "(sports=${sportsCount()}, " +
                            "iptv-org=${iptvOrgCount()}, ntv.cx=${ntvCxCount()}, " +
                            "adultswim=${adultSwimCount()}, " +
                            "blocked_thetvapp=${lastSync.blockedTheTvApp})",
                    )
                    onRefreshComplete?.invoke()
                } catch (exc: Exception) {
                    mergeJob.cancel()
                    Log.w(TAG, "Supplement sync failed — keeping cache", exc)
                    retainOrRecoverCache(daddyChannels)
                } finally {
                    refreshInFlight = false
                }
            }
        }
    }

    fun recoverFromDiskIfNeeded(daddyChannels: List<Channel>): Int {
        if (cached.isNotEmpty()) return cached.size
        retainOrRecoverCache(daddyChannels)
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
            sportsChannels = sportsCount(),
        )
        Log.i(TAG, "Recovered ${recovered.size} supplement channels from disk caches")
    }

    private fun recoverChannelsFromDiskCaches(daddyChannels: List<Channel>): List<SupplementChannel> {
        if (!enabled()) return emptyList()
        val recovered = mutableListOf<SupplementChannel>()
        if (ntvCxEnabled()) {
            val catalog = ntvCxCatalogStore.loadCatalog()
            if (catalog.isNotEmpty()) {
                recovered += NtvCxCdnLiveSource.buildChannels(
                    catalog = catalog,
                    daddyChannels = daddyChannels,
                    mergeMode = environment.supplementNtvCxImportMode,
                    nameIndex = null,
                )
            }
        }
        return recovered
    }

    private suspend fun mergeSupplements(
        daddyChannels: List<Channel>,
        dlhdScheduleBaseUrl: String? = null,
    ): List<SupplementChannel> =
        coroutineScope {
        var tvAppEventsScanned = 0
        var specialEventGuides = 0
        var dlhdEventStreams = 0
        val specialEvents = if (sportsEnabled()) {
            val scheduleBase = dlhdScheduleBaseUrl?.trim()?.trimEnd('/').orEmpty()
                .ifEmpty { environment.dlhdBaseUrl.trimEnd('/') }
            val (tvAppStats, bundle) = fetchSpecialEventsBundle(scheduleBase)
            tvAppEventsScanned = tvAppStats.eventsScanned
            applySpecialEventsBundle(bundle, tvAppEventsScanned)
            specialEventGuides = specialEventGuideCount()
            dlhdEventStreams = dlhdEventStreamCount()
            bundle.channels
        } else {
            emptyList()
        }

        if (environment.gatewayEpgEnabled && iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
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

        if (environment.gatewayEpgEnabled && iptvOrgEnabled() && environment.iptvOrgEpgEnabled) {
            runCatching {
                iptvOrgEpgRepository.refresh(
                    environment.iptvOrgEpgUrl.takeIf { it.isNotBlank() },
                )
            }.onFailure { exc -> Log.w(TAG, "iptv-org EPG refresh failed", exc) }
        }

        lastSync = SyncSnapshot(
            sportsChannels = specialEvents.size,
            specialEventGuides = specialEventGuides,
            dlhdEventStreams = dlhdEventStreams,
            sportsEventsScanned = tvAppEventsScanned,
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

        specialEvents + iptvOrg + ntvCx + adultSwim
    }

    private suspend fun fetchSpecialEventsBundle(
        scheduleBase: String,
    ): Pair<TheTvAppSportsResolver.ResolveStats, SpecialEventsMerger.EpgBundle> = coroutineScope {
        val tvAppDeferred = async {
            runCatching { sportsResolver.resolveFromNetwork() }
                .getOrElse { exc ->
                    Log.w(TAG, "TheTvApp sports resolver failed", exc)
                    emptyList<SupplementChannel>() to TheTvAppSportsResolver.ResolveStats()
                }
        }
        val dlhdDeferred = async {
            runCatching {
                resolveDlhdScheduleEvents(scheduleBase)
            }.getOrElse { exc ->
                Log.w(TAG, "DLHD schedule resolve failed (base=$scheduleBase)", exc)
                emptyList<DaddyLiveEventResolver.ParsedEvent>() to DaddyLiveEventResolver.ResolveStats()
            }
        }
        val (tvChannels, tvStats) = tvAppDeferred.await()
        val (dlhdEvents, dlhdStats) = dlhdDeferred.await()
        val rawBundle = if (dlhdEvents.isEmpty()) {
            SpecialEventsMerger.EpgBundle(
                channels = tvChannels.map { it.copy(groupTitle = GroupTitleResolver.SPECIAL_EVENTS) },
            )
        } else {
            SpecialEventsMerger.buildFromParsed(
                dlhdEvents = dlhdEvents,
                dlhdStats = dlhdStats,
                theTvAppChannels = tvChannels,
                maxStreams = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
            )
        }
        val bundle = EventLifecycleManager.dedupeBundle(rawBundle)
        tvStats to bundle
    }

    private fun applySpecialEventsBundle(
        bundle: SpecialEventsMerger.EpgBundle,
        tvAppEventsScanned: Int,
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
            sportsEventsScanned = tvAppEventsScanned,
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
        val scraped = EventMetadataScraper.scrapeChannels(channels)
        eventMetadata = scraped
        eventMetadataStore.writeAll(scraped)
        Log.d(TAG, "Event metadata synced: ${scraped.size} entries")
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
        EventLifecycleManager.CatalogState(
            channels = cached,
            guideSchedules = guideSchedules,
        )

    private fun applyLifecyclePrune(outcome: EventLifecycleManager.PruneOutcome): Boolean {
        if (!outcome.changed) return false
        cached = outcome.state.channels
        guideSchedules = outcome.state.guideSchedules
        store.writeChannels(cached)
        if (outcome.state.guideSchedules.isEmpty()) {
            store.clearGuideSchedules()
        } else {
            store.writeGuideSchedules(outcome.state.guideSchedules)
        }
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
    ) {
        if (channels.isEmpty()) {
            store.sportsEpgFile.delete()
            return
        }
        runCatching {
            SpecialEventsEpgGenerator.writeXml(
                SpecialEventsEpgGenerator.programmesForBundle(channels, programmes),
                store.sportsEpgFile,
            )
        }.onFailure { exc ->
            Log.w(TAG, "Special Events EPG write failed", exc)
        }
    }

    /** Tries active mirror then configured mirrors until schedule JSON returns events. */
    private fun resolveDlhdScheduleEvents(
        primaryBase: String,
    ): Pair<List<DaddyLiveEventResolver.ParsedEvent>, DaddyLiveEventResolver.ResolveStats> {
        val mirrorBases = linkedSetOf<String>()
        mirrorBases += primaryBase.trimEnd('/')
        mirrorBases += environment.dlhdBaseUrl.trimEnd('/')
        environment.mirrorUrls.forEach { mirrorBases += it.trimEnd('/') }

        for (base in mirrorBases) {
            if (base.isEmpty()) continue
            val (events, stats) = dlhdEventResolver.resolveFromNetwork(base)
            if (events.isEmpty()) {
                Log.d(TAG, "DLHD schedule empty on $base (tv=${stats.tvEvents} tv2=${stats.tv2Events})")
                continue
            }
            Log.i(
                TAG,
                "DLHD schedule from $base: tv=${stats.tvEvents} tv2=${stats.tv2Events} links=${stats.streamLinks}",
            )
            return events to stats
        }
        return emptyList<DaddyLiveEventResolver.ParsedEvent>() to DaddyLiveEventResolver.ResolveStats()
    }

    companion object {
        private const val TAG = "SupplementSource"
        /** iptv-org fetches 39 playlists + FAST EPG on slow STBs (e.g. MiTV) can exceed 2 min. */
        private const val SYNC_MAX_MS = 900_000L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(SupplementConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(SupplementConfig.DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
