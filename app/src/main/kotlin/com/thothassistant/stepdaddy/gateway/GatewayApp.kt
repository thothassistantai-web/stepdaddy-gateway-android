package com.thothassistant.stepdaddy.gateway

import android.app.Application
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.EpgShareIdBridge
import com.thothassistant.stepdaddy.gateway.epg.EpgStore
import com.thothassistant.stepdaddy.gateway.epg.TvtvUsEpgFetcher
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.TvgIdResolver
import com.thothassistant.stepdaddy.gateway.sidecar.EmbeddedSidecarRepository
import com.thothassistant.stepdaddy.gateway.upstream.CategoryOverrideStore
import com.thothassistant.stepdaddy.gateway.upstream.ChannelMetaStore
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistCache
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private var _embeddedSidecarRepository: EmbeddedSidecarRepository? = null
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

    val embeddedSidecarRepository: EmbeddedSidecarRepository
        get() = _embeddedSidecarRepository ?: error("Gateway components not initialized")

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

    val epgManager: EpgManager
        get() = _epgManager ?: error("Gateway components not initialized")

    private var _appUpdateCoordinator: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator? = null

    val appUpdateCoordinator: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator
        get() = _appUpdateCoordinator
            ?: com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator(
                this,
                gatewayEnvironment,
            ).also { _appUpdateCoordinator = it }

    override fun onCreate() {
        super.onCreate()
        // Keep Application.onCreate minimal — heavy init during BOOT_COMPLETED ANRs the
        // process before WorkManager can bind SystemJobService (FUSA sticks).
        GatewayNotifier.createChannels(this)
        gatewayEnvironment = GatewayEnvironment(this)
        gatewayEnvironment.ensureEmbeddedSidecarUrl()
        gatewayEnvironment.clearBootStaleState()

        if (gatewayEnvironment.startOnBoot) {
            ScreenWakeRegistrar.register(this)
            bootKickExecutor.execute {
                GatewayStartHelper.startIfNeeded(this@GatewayApp, "Application", allowReschedule = false)
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
                _playlistCache = PlaylistCache()
                _embeddedSidecarRepository = EmbeddedSidecarRepository(this@GatewayApp)
                val store = EpgStore(this@GatewayApp)
                _epgChannelMapper = EpgChannelMapper(this@GatewayApp)
                _iptvOrgNameIndex = IptvOrgNameIndex(this@GatewayApp)
                _tvgIdResolver = TvgIdResolver(_iptvOrgNameIndex!!, _epgChannelMapper!!)
                _epgShareIdBridge = EpgShareIdBridge(this@GatewayApp)
                _channelMetaStore = ChannelMetaStore(this@GatewayApp)
                _logoResolver = LogoResolver(this@GatewayApp)
                CategoryOverrideStore.ensureLoaded(this@GatewayApp)
                _supplementSource = SupplementSource(
                    this@GatewayApp,
                    gatewayEnvironment,
                    nameIndex = _iptvOrgNameIndex!!,
                    epgChannelMapper = _epgChannelMapper!!,
                )
                val tvtvFetcher = TvtvUsEpgFetcher(this@GatewayApp, store)
                _epgManager = EpgManager(
                    store,
                    _epgChannelMapper!!,
                    _supplementSource!!,
                    _epgShareIdBridge!!,
                    tvtvFetcher,
                    isGatewayEpgEnabled = { gatewayEnvironment.gatewayEpgEnabled },
                )
            }
        }
        if (!componentsReady.isCompleted) {
            componentsReady.complete(Unit)
        }
    }
}
