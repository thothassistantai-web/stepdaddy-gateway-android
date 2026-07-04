package com.thothassistant.stepdaddy.gateway

import android.app.Application
import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgShareIdBridge
import com.thothassistant.stepdaddy.gateway.epg.EpgStore
import com.thothassistant.stepdaddy.gateway.epg.TvtvUsEpgFetcher
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.TvgIdResolver
import com.thothassistant.stepdaddy.gateway.upstream.CategoryOverrideStore
import com.thothassistant.stepdaddy.gateway.upstream.ChannelMetaStore
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.EventStreamHealthMonitor
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GatewayApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val componentsReady = CompletableDeferred<Unit>()
    private val initMutex = Mutex()
    private val bootKickExecutor = Executors.newSingleThreadExecutor()

    lateinit var gatewayEnvironment: GatewayEnvironment
        private set

    private var _playlistCache: PlaylistCache? = null
    private var _epgChannelMapper: EpgChannelMapper? = null
    private var _iptvOrgNameIndex: IptvOrgNameIndex? = null
    private var _tvgIdResolver: TvgIdResolver? = null
    private var _epgShareIdBridge: EpgShareIdBridge? = null
    private var _channelMetaStore: ChannelMetaStore? = null
    private var _logoResolver: LogoResolver? = null
    private var _supplementSource: SupplementSource? = null
    private var _epgManager: EpgManager? = null

    val playlistCache: PlaylistCache
        get() = _playlistCache ?: error("Gateway components not initialized")

    val epgChannelMapper: EpgChannelMapper
        get() = _epgChannelMapper ?: error("Gateway components not initialized")

    val tvgIdResolver: TvgIdResolver
        get() = _tvgIdResolver ?: error("Gateway components not initialized")

    val epgShareIdBridge: EpgShareIdBridge
        get() = _epgShareIdBridge ?: error("Gateway components not initialized")

    val channelMetaStore: ChannelMetaStore
        get() = _channelMetaStore ?: error("Gateway components not initialized")

    val logoResolver: LogoResolver
        get() = _logoResolver ?: error("Gateway components not initialized")

    val supplementSource: SupplementSource
        get() = _supplementSource ?: error("Gateway components not initialized")

    val eventStreamHealthMonitor: EventStreamHealthMonitor
        get() = supplementSource.eventStreamHealthMonitor()

    val epgManager: EpgManager
        get() = _epgManager ?: error("Gateway components not initialized")

    private var _appUpdateCoordinator: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator? = null

    val appUpdateCoordinator: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator
        get() = _appUpdateCoordinator
            ?: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator(
                this,
                gatewayEnvironment,
            ).also { _appUpdateCoordinator = it }

    private var _tiviMateUpdateCoordinator:
        com.thothassistant.stepdaddy.gateway.update.TiviMateUpdateCoordinator? = null

    val tiviMateUpdateCoordinator: com.thothassistant.stepdaddy.gateway.update.TiviMateUpdateCoordinator
        get() = _tiviMateUpdateCoordinator
            ?: com.thothassistant.stepdaddy.gateway.update.TiviMateUpdateCoordinator(
                this,
                gatewayEnvironment,
            ).also { _tiviMateUpdateCoordinator = it }

    override fun onCreate() {
        super.onCreate()
        // Keep Application.onCreate minimal — heavy init during BOOT_COMPLETED ANRs the
        // process before WorkManager can bind SystemJobService (FUSA sticks).
        GatewayNotifier.createChannels(this)
        gatewayEnvironment = GatewayEnvironment(this)
        gatewayEnvironment.clearBootStaleState()

        if (gatewayEnvironment.startOnBoot) {
            ScreenWakeRegistrar.register(this)
            bootKickExecutor.execute {
                if (FireTvDevice.isFireTv(this@GatewayApp)) {
                    // Fire Stick: BootReceiver owns the delayed start. Only arm keep-alive
                    // here so Application.onCreate does not race heavy init during boot.
                    GatewayStartHelper.scheduleFireBootFallbacks(this@GatewayApp)
                } else {
                    GatewayStartHelper.startIfNeeded(this@GatewayApp, "Application", allowReschedule = false)
                }
            }
        }

        appScope.launch { initComponentsIfNeeded() }
    }

    suspend fun awaitComponents() {
        initComponentsIfNeeded()
        componentsReady.await()
    }

    private suspend fun initComponentsIfNeeded() {
        if (_epgManager != null) {
            if (!componentsReady.isCompleted) componentsReady.complete(Unit)
            return
        }
        initMutex.withLock {
            if (_epgManager != null) return@withLock
            withContext(Dispatchers.IO) {
                coroutineScope {
                    _playlistCache = PlaylistCache()
                    val storeDeferred = async { EpgStore(this@GatewayApp) }
                    val mapperDeferred = async { EpgChannelMapper(this@GatewayApp) }
                    val metaDeferred = async { ChannelMetaStore(this@GatewayApp) }
                    val categoryDeferred = async {
                        CategoryOverrideStore.ensureLoaded(this@GatewayApp)
                    }
                    val nameIndexDeferred = async { IptvOrgNameIndex(this@GatewayApp) }
                    val logoDeferred = async { LogoResolver(this@GatewayApp) }
                    val bridgeDeferred = async { EpgShareIdBridge(this@GatewayApp) }

                    val store = storeDeferred.await()
                    val mapper = mapperDeferred.await()
                    val meta = metaDeferred.await()
                    categoryDeferred.await()
                    val nameIndex = nameIndexDeferred.await()
                    val logo = logoDeferred.await()
                    val bridge = bridgeDeferred.await()

                    _epgChannelMapper = mapper
                    _iptvOrgNameIndex = nameIndex
                    _tvgIdResolver = TvgIdResolver(nameIndex, mapper)
                    _epgShareIdBridge = bridge
                    _channelMetaStore = meta
                    _logoResolver = logo
                    _supplementSource = SupplementSource(
                        this@GatewayApp,
                        gatewayEnvironment,
                        nameIndex = nameIndex,
                        epgChannelMapper = mapper,
                        logoResolver = logo,
                        channelMetaStore = meta,
                    )
                    val tvtvFetcher = TvtvUsEpgFetcher(this@GatewayApp, store)
                    _epgManager = EpgManager(
                        store,
                        mapper,
                        _supplementSource!!,
                        bridge,
                        tvtvFetcher,
                        isGatewayEpgEnabled = { gatewayEnvironment.gatewayEpgEnabled },
                    )
                }
            }
        }
        if (!componentsReady.isCompleted) {
            componentsReady.complete(Unit)
        }
        // Fire Stick: skip post-init EPG invalidate — rebuild spikes RAM and trips LMK.
        if (!FireTvDevice.isFireTv(this)) {
            appScope.launch(Dispatchers.IO) {
                val store = EpgStore(this@GatewayApp)
                maybeInvalidateEpgForBridgeFix(store)
                val mapper = _epgChannelMapper ?: return@launch
                if (mapper.mappingMigrationApplied) {
                    store.invalidateBuild()
                    Log.i("GatewayApp", "EPG rebuild scheduled after channel mapping correction")
                }
            }
        }
    }

    private fun maybeInvalidateEpgForBridgeFix(store: EpgStore) {
        val prefs = getSharedPreferences("epg", MODE_PRIVATE)
        val key = "id_bridge_lifetime_fix_v1"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        store.invalidateBuild()
        Log.i("GatewayApp", "EPG rebuild scheduled after Lifetime/USA bridge correction")
    }
}
