package com.thothassistant.stepdaddy.gateway

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.admin.GatewayAdminController
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayDiagnostics
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayMessageBus
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.SupplementConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class ServerService : LifecycleService() {
    private lateinit var environment: GatewayEnvironment
    private lateinit var daddyLiveClient: DaddyLiveClient
    private lateinit var epgManager: com.thothassistant.stepdaddy.gateway.epg.EpgManager
    private var gatewayServer: GatewayServer? = null
    private var controlPortServer: ControlPortServer? = null
    private var streamHealthWatchdog: StreamHealthWatchdog? = null
    private val startMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var startInFlight = false
    private var httpHealthCheckPosted = false
    private var tiviMateWatchPosted = false

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        val app = application as GatewayApp
        environment = app.gatewayEnvironment
        // Android requires startForeground within ~5s of startForegroundService() — do this
        // before channel preload, EPG, or HTTP engine work (boot path crashes otherwise).
        GatewayNotifier.createChannels(this)
        startForeground(
            GatewayNotifier.NOTIFICATION_ID_ONGOING,
            GatewayNotifier.buildOngoingNotification(
                this,
                GatewayNotifier.GatewayState.STARTING,
                environment.loopbackBase(),
            ),
        )
        environment.recordServiceStart()
        GatewayHud.initForService(environment)
        if (LowRamTvDevice.needsMemoryLite(this)) {
            // Low-RAM sticks: LMK kills FGS during catalog load. Keep boot alarms armed until
            // /health reports channels — do not cancel merely because FGS started.
            fireHeavyWorkScheduled.set(false)
            GatewayStartHelper.scheduleFireBootFallbacks(this)
            FireMemoryGuard.install(this) { releaseFireCaches() }
        } else {
            GatewayStartHelper.cancelBootFallbacks(this)
            GatewayStartHelper.resetFallbacksScheduled()
        }
        ScreenWakeRegistrar.register(this)
        GatewayMessageBus.postBoot("ServerService")
        GatewayDiagnostics.info(TAG, "Foreground service created")
        GatewayPackageGuard.resolveSiblingConflict(this)
        scheduleHttpHealthCheck()
        scheduleTiviMateResumeWatch()
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as GatewayApp
            app.awaitComponents()
            epgManager = app.epgManager
        }
        // Start HTTP without waiting for onStartCommand binder hop (boot path).
        startGateway()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopGateway()
            ACTION_ENSURE_GATEWAY -> ensureGatewayListening()
            ACTION_ENSURE_READY -> {
                ensureGatewayListening()
                lifecycleScope.launch(Dispatchers.IO) {
                    GatewayStartHelper.ensureGatewayReady(this@ServerService)
                }
            }
            ACTION_REFRESH_SUPPLEMENTS -> {
                ensureGatewayListening()
                lifecycleScope.launch(Dispatchers.IO) {
                    refreshSupplementsFromSettings()
                }
            }
            else -> startGateway()
        }
        return START_STICKY
    }

    /** Nudge from [GatewayStartHelper] when FGS is alive but CIO engine dropped. */
    private fun ensureGatewayListening() {
        if (gatewayServer?.isRunning == true) {
            environment.serverRunning = true
            updateRunningNotification()
            return
        }
        startGateway(skipReadySurface = true)
    }

    private fun startGateway(skipReadySurface: Boolean = false) {
        if (gatewayServer?.isRunning == true) {
            environment.serverRunning = true
            updateRunningNotification()
            return
        }
        if (startInFlight) {
            return
        }
        startForeground(
            GatewayNotifier.NOTIFICATION_ID_ONGOING,
            GatewayNotifier.buildOngoingNotification(
                this,
                GatewayNotifier.GatewayState.STARTING,
                environment.loopbackBase(),
            ),
        )
        startInFlight = true
        lifecycleScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                if (gatewayServer?.isRunning == true) {
                    startInFlight = false
                    return@withLock
                }
                try {
                    val app = application as GatewayApp
                    val componentsReady = withTimeoutOrNull(COMPONENT_INIT_MAX_WAIT_MS) {
                        app.awaitComponents()
                    }
                    if (componentsReady == null) {
                        GatewayDiagnostics.error(
                            TAG,
                            "Gateway components init timed out after ${COMPONENT_INIT_MAX_WAIT_MS}ms; will retry",
                        )
                        return@withLock
                    }
                    if (!::epgManager.isInitialized) {
                        epgManager = app.epgManager
                    }
                    val client = ensureDaddyLiveClient(app)
                    val cacheReady = withTimeoutOrNull(BOOT_CHANNEL_LOAD_MAX_WAIT_MS) {
                        client.awaitInitialLoad()
                    }
                    if (cacheReady == null) {
                        GatewayDiagnostics.info(
                            TAG,
                            "Channel disk cache still loading after ${BOOT_CHANNEL_LOAD_MAX_WAIT_MS}ms; starting HTTP anyway",
                        )
                    }
                    val adminController = GatewayAdminController(
                        context = this@ServerService,
                        environment = environment,
                        client = client,
                        epgManager = epgManager,
                        app = app,
                        logoResolver = app.logoResolver,
                        prewarmPlaylist = { gatewayServer?.prewarmPlaylist() },
                        stopGatewayAction = { stopGateway() },
                        restartHttpAction = { restartGatewayAfterFailure() },
                        restartFullAction = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                startMutex.withLock {
                                    gatewayServer?.stop()
                                    gatewayServer = null
                                }
                                delay(500L)
                                startGateway(skipReadySurface = true)
                            }
                        },
                    )
                    val server = GatewayServer(
                        this@ServerService,
                        environment,
                        client,
                        epgManager,
                        app.logoResolver,
                        app.channelMetaStore,
                        app.supplementSource,
                        app.playlistCache,
                        adminController,
                    )
                    gatewayServer = server
                    val memoryLite = LowRamTvDevice.needsMemoryLite(this@ServerService)
                    app.supplementSource.onRefreshComplete = {
                        val sync = app.supplementSource.syncSnapshot()
                        GatewayDiagnostics.info(
                            TAG,
                            buildString {
                                append("Supplement sync done")
                                append(" · sports ${sync.sportsChannels}")
                                if (sync.specialEventGuides > 0) {
                                    append(" (${sync.specialEventGuides} guides")
                                    append(", ${sync.dlhdEventStreams} dlhd)")
                                } else if (sync.sportsEventsScanned > 0) {
                                    append(" (${sync.sportsEventsScanned} events scanned)")
                                }
                                append(" · IPTV-org ${sync.iptvOrgChannels}")
                                if (sync.iptvOrgPlaylistsFetched > 0) {
                                    append(" (${sync.iptvOrgPlaylistsFetched} playlists)")
                                }
                                append(" · NTV ${sync.ntvCxChannels}")
                                append(" · Adult Swim ${sync.adultSwimChannels}")
                                if (sync.adultSwimProbed > 0) {
                                    append(" (${sync.adultSwimProbeOk}/${sync.adultSwimProbed} probes ok)")
                                }
                            },
                        )
                        if (!memoryLite) {
                            server.prewarmPlaylist()
                            epgManager.scheduleRefresh(client.channels, force = true)
                        } else {
                            app.playlistCache.invalidate()
                        }
                    }
                    app.supplementSource.onSpecialEventsChanged = {
                        if (!memoryLite) {
                            server.prewarmPlaylist()
                            epgManager.scheduleRefresh(client.channels, force = true)
                        } else {
                            app.playlistCache.invalidate()
                        }
                    }
                    app.supplementSource.onDlhdEventHealthChanged = {
                        app.playlistCache.invalidate()
                        if (!memoryLite) {
                            server.prewarmPlaylist()
                        }
                    }
                    server.start()
                    // Fire Stick: skip playlist prewarm + EPG at listen — peak RAM kills FGS.
                    if (!memoryLite) {
                        server.prewarmPlaylist()
                        scheduleImmediateBootEpgFastPass(client)
                    }
                    environment.serverRunning = true
                    controlPortServer?.stop()
                    controlPortServer = ControlPortServer(environment, client).apply { start() }
                    GatewayDiagnostics.info(
                        TAG,
                        "Control port HTTP API emulating StepDaddy patch at ${TiviMateController.HTTP_CONTROL_BASE}",
                    )
                    streamHealthWatchdog?.stop()
                    streamHealthWatchdog = null
                    // Fire Stick: stream probes allocate manifests and trip LMK; defer with heavy work.
                    if (!memoryLite) {
                        streamHealthWatchdog = StreamHealthWatchdog(
                            client = client,
                            environment = environment,
                            onPersistentFailure = { restartGatewayAfterFailure() },
                        ).also { it.start() }
                    }
                    client.reportHealthyStart()
                    val channelCount = client.channels.size
                    mainHandler.post {
                        GatewayHud.onHttpListening(this@ServerService, channelCount, skipReadySurface)
                    }
                    updateRunningNotification()
                    GatewayStartHelper.schedulePeriodicEnsureAlive(this@ServerService)
                    if (channelCount > 0 && GatewayStartHelper.isGatewayHealthy(this@ServerService)) {
                        // Catalog ready — safe to drop Fire Stick keep-alive alarms.
                        GatewayStartHelper.cancelBootFallbacks(this@ServerService)
                    } else if (memoryLite) {
                        GatewayStartHelper.scheduleFireBootFallbacks(this@ServerService)
                    }
                    GatewayDiagnostics.info(TAG, "Gateway listening on ${environment.loopbackBase()} ($channelCount channels)")
                    if (!memoryLite) {
                        scheduleDeferredBootEpgBuild(client)
                    } else {
                        scheduleFireDeferredHeavyWork(client)
                    }
                    if (client.channels.isEmpty()) {
                        client.scheduleChannelRefresh(force = true) {
                            updateRunningNotification()
                        }
                    }
                    if (!memoryLite) {
                        epgManager.schedulePeriodicRefresh { daddyLiveClient.channels }
                        app.supplementSource.schedulePeriodicRefresh { daddyLiveClient.channels }
                        app.supplementSource.schedulePeriodicSpecialEventsMaintenance {
                            daddyLiveClient.activeBaseUrl
                        }
                        if (SupplementConfig.DLHD_EVENT_HEALTH_PROBES_ENABLED) {
                            app.eventStreamHealthMonitor.start { channelId ->
                                try {
                                    withTimeout(GatewayConfig.WATCHDOG_PROBE_TIMEOUT_MS) {
                                        daddyLiveClient.resolveStream(
                                            channelId,
                                            useProxy = true,
                                            apiUrl = environment.loopbackBase(),
                                        )
                                    }
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        }
                    }
                    scheduleDeferredBootChannelRefresh(skipReadySurface)
                } catch (exc: Exception) {
                    GatewayDiagnostics.error(TAG, "Failed to start gateway on port ${environment.port}", exc)
                    gatewayServer = null
                    environment.serverRunning = false
                    val message = exc.message ?: getString(R.string.notification_error_unknown)
                    startForeground(
                        GatewayNotifier.NOTIFICATION_ID_ONGOING,
                        GatewayNotifier.buildOngoingNotification(
                            this@ServerService,
                            GatewayNotifier.GatewayState.ERROR,
                            environment.loopbackBase(),
                            errorMessage = message,
                        ),
                    )
                    mainHandler.post {
                        GatewayHud.onFailed(this@ServerService, message)
                    }
                } finally {
                    startInFlight = false
                    if (gatewayServer?.isRunning != true) {
                        mainHandler.postDelayed(
                            { startGateway(skipReadySurface = true) },
                            COMPONENT_INIT_RETRY_MS,
                        )
                    }
                }
            }
        }
    }

    /** Fast EPG pass from disk caches as soon as channels are available (no 20s boot defer). */
    private fun scheduleImmediateBootEpgFastPass(client: DaddyLiveClient) {
        if (!::epgManager.isInitialized || !epgManager.needsBuild()) return
        if (client.channels.isEmpty()) return
        epgManager.scheduleRefresh(
            client.channels,
            force = true,
            tvtvGapFill = false,
        )
    }

    /** Fallback when catalog was empty at listen time — retry after deferred channel refresh. */
    private fun scheduleDeferredBootEpgBuild(client: DaddyLiveClient) {
        if (!epgManager.needsBuild()) return
        lifecycleScope.launch(Dispatchers.IO) {
            delay(BOOT_EPG_BUILD_DEFER_MS)
            if (!isServiceActive || !::epgManager.isInitialized) return@launch
            if (!epgManager.needsBuild()) return@launch
            epgManager.scheduleRefresh(
                client.channels,
                force = true,
                tvtvGapFill = false,
            )
        }
    }

    /**
     * Fire Stick: after a long settle window, enable EPG/supplement periodic work.
     * Immediate post-listen work is what trips LMK (`prcp FGS`).
     */
    private fun scheduleFireDeferredHeavyWork(client: DaddyLiveClient) {
        if (!fireHeavyWorkScheduled.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val deferMs =
                when {
                    FireTvDevice.isFireTv(this@ServerService) -> FIRE_HEAVY_WORK_DEFER_MS
                    LowRamTvDevice.isOnnStick(this@ServerService) -> ONN_HEAVY_WORK_DEFER_MS
                    else -> FIRE_HEAVY_WORK_DEFER_MS
                }
            delay(deferMs)
            if (!isServiceActive) return@launch
            val app = application as GatewayApp
            val label =
                when {
                    FireTvDevice.isFireTv(this@ServerService) -> "Fire Stick"
                    LowRamTvDevice.isOnnStick(this@ServerService) -> "Onn"
                    else -> "low-RAM TV"
                }
            Log.i(TAG, "$label: starting deferred heavy work after settle")
            if (::epgManager.isInitialized && epgManager.needsBuild() && client.channels.isNotEmpty()) {
                epgManager.scheduleRefresh(client.channels, force = true, tvtvGapFill = false)
            }
            if (::epgManager.isInitialized) {
                epgManager.schedulePeriodicRefresh { daddyLiveClient.channels }
            }
            app.supplementSource.schedulePeriodicRefresh { daddyLiveClient.channels }
            app.supplementSource.schedulePeriodicSpecialEventsMaintenance {
                daddyLiveClient.activeBaseUrl
            }
            if (streamHealthWatchdog == null && ::daddyLiveClient.isInitialized) {
                streamHealthWatchdog = StreamHealthWatchdog(
                    client = daddyLiveClient,
                    environment = environment,
                    onPersistentFailure = { restartGatewayAfterFailure() },
                ).also { it.start() }
            }
        }
    }

    private fun releaseFireCaches() {
        runCatching {
            val app = application as GatewayApp
            app.playlistCache.releaseMemory()
            app.logoResolver.releaseMemory()
            app.supplementSource.releaseMemory()
            if (::daddyLiveClient.isInitialized) {
                daddyLiveClient.releaseMemoryCaches()
            }
        }
    }

    private suspend fun ensureDaddyLiveClient(app: GatewayApp): DaddyLiveClient {
        if (::daddyLiveClient.isInitialized) {
            return daddyLiveClient
        }
        val httpClient =
            if (LowRamTvDevice.needsMemoryLite(this)) {
                FireMemoryGuard.compactHttpClient(
                    connectSec = GatewayConfig.UPSTREAM_CONNECT_TIMEOUT_SEC,
                    readSec = GatewayConfig.UPSTREAM_READ_TIMEOUT_SEC,
                    writeSec = GatewayConfig.UPSTREAM_WRITE_TIMEOUT_SEC,
                    callSec = GatewayConfig.UPSTREAM_CALL_TIMEOUT_SEC,
                )
            } else {
                com.thothassistant.stepdaddy.gateway.upstream.ResportzParser.defaultClient()
            }
        daddyLiveClient = DaddyLiveClient(
            environment,
            app.epgChannelMapper,
            app.tvgIdResolver,
            app.logoResolver,
            app.channelMetaStore,
            client = httpClient,
            context = this,
        )
        app.activeDaddyLiveClient = daddyLiveClient
        return daddyLiveClient
    }

    /**
     * Serve disk-cached channels first; defer upstream refresh so boot-time CPU/network
     * stays available for HTTP listen + first playlist/stream requests.
     */
    private fun scheduleDeferredBootChannelRefresh(skipReadySurface: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val memoryLite = LowRamTvDevice.needsMemoryLite(this@ServerService)
            val hasChannels =
                ::daddyLiveClient.isInitialized && daddyLiveClient.channels.isNotEmpty()
            val deferMs =
                if (!hasChannels) {
                    when {
                        FireTvDevice.isFireTv(this@ServerService) -> FIRE_EMPTY_CHANNEL_REFRESH_DEFER_MS
                        LowRamTvDevice.isOnnStick(this@ServerService) -> ONN_EMPTY_CHANNEL_REFRESH_DEFER_MS
                        memoryLite -> FIRE_EMPTY_CHANNEL_REFRESH_DEFER_MS
                        else -> 8_000L
                    }
                } else if (memoryLite) {
                    when {
                        FireTvDevice.isFireTv(this@ServerService) -> FIRE_BOOT_CHANNEL_REFRESH_DEFER_MS
                        LowRamTvDevice.isOnnStick(this@ServerService) -> ONN_BOOT_CHANNEL_REFRESH_DEFER_MS
                        else -> FIRE_BOOT_CHANNEL_REFRESH_DEFER_MS
                    }
                } else {
                    BOOT_CHANNEL_REFRESH_DEFER_MS
                }
            delay(deferMs)
            if (!isServiceActive || !::daddyLiveClient.isInitialized) return@launch
            val app = application as GatewayApp
            // Fire Stick only: disk DaddyLive catalog is enough; skip supplement network refresh in LMK window.
            if (FireTvDevice.isFireTv(this@ServerService) && hasChannels) {
                if (!skipReadySurface) {
                    mainHandler.post {
                        GatewayHud.onCatalogReady(
                            this@ServerService,
                            daddyLiveClient.channels.size,
                            environment,
                            launchTivimate = false,
                        )
                    }
                }
                if (GatewayStartHelper.isGatewayHealthy(this@ServerService)) {
                    GatewayStartHelper.cancelBootFallbacks(this@ServerService)
                }
                return@launch
            }
            daddyLiveClient.scheduleChannelRefresh(force = true) {
                updateRunningNotification()
                daddyLiveClient.schedulePrewarmDelayed()
                app.tvgIdResolver.backfillUnmapped(
                    this@ServerService,
                    app.epgChannelMapper,
                    daddyLiveClient.channels,
                )
                app.logoResolver.schedulePrewarm(
                    daddyLiveClient.channels.map { it.name to it.tvgId },
                )
                if (!skipReadySurface) {
                    mainHandler.post {
                        GatewayHud.onCatalogReady(
                            this@ServerService,
                            daddyLiveClient.channels.size,
                            environment,
                            launchTivimate = false,
                        )
                    }
                }
                if (GatewayStartHelper.isGatewayHealthy(this@ServerService)) {
                    GatewayStartHelper.cancelBootFallbacks(this@ServerService)
                }
            }
            runCatching {
                app.supplementSource.recoverFromDiskIfNeeded(daddyLiveClient.channels)
                when {
                    FireTvDevice.isFireTv(this@ServerService) -> delay(FIRE_SUPPLEMENT_NETWORK_GAP_MS)
                    LowRamTvDevice.isOnnStick(this@ServerService) -> delay(ONN_SUPPLEMENT_NETWORK_GAP_MS)
                }
                app.supplementSource.refresh(
                    daddyLiveClient.channels,
                    force = true,
                    dlhdScheduleBaseUrl = daddyLiveClient.activeBaseUrl,
                )
            }.onFailure { exc ->
                Log.w(TAG, "Boot supplement refresh failed", exc)
            }
            if (GatewayStartHelper.isGatewayHealthy(this@ServerService)) {
                GatewayStartHelper.cancelBootFallbacks(this@ServerService)
            }
            if (!memoryLite && (epgManager.needsBuild() || epgManager.isServeStale())) {
                epgManager.scheduleRefresh(
                    daddyLiveClient.channels,
                    force = epgManager.needsBuild(),
                    tvtvGapFill = true,
                )
            }
        }
    }

    private suspend fun refreshSupplementsFromSettings() {
        val app = application as GatewayApp
        app.awaitComponents()
        if (!::daddyLiveClient.isInitialized) return
        app.playlistCache.invalidate()
        runCatching {
            app.supplementSource.refresh(
                daddyLiveClient.channels,
                force = true,
                dlhdScheduleBaseUrl = daddyLiveClient.activeBaseUrl,
            )
        }.onFailure { exc ->
            Log.w(TAG, "Settings-triggered supplement refresh failed", exc)
        }
        epgManager.scheduleRefresh(daddyLiveClient.channels, force = true)
        updateRunningNotification()
    }

    private fun updateRunningNotification() {
        val channelCount =
            if (::daddyLiveClient.isInitialized) daddyLiveClient.channels.size else lastKnownChannelCount
        lastKnownChannelCount = channelCount
        if (channelCount > 0) {
            GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.READY)
        } else if (gatewayServer?.isRunning == true) {
            GatewayHealth.setReadinessPhase(GatewayHealth.ReadinessPhase.WAITING_CHANNELS)
        }
        startForeground(
            GatewayNotifier.NOTIFICATION_ID_ONGOING,
            GatewayNotifier.buildOngoingNotification(
                this,
                GatewayNotifier.GatewayState.RUNNING,
                environment.loopbackBase(),
                channelCount = channelCount,
            ),
        )
    }

    private fun stopGateway() {
        streamHealthWatchdog?.stop()
        streamHealthWatchdog = null
        runCatching {
            (application as? GatewayApp)?.eventStreamHealthMonitor?.stop()
        }
        controlPortServer?.stop()
        controlPortServer = null
        gatewayServer?.stop()
        gatewayServer = null
        environment.serverRunning = false
        isServiceActive = false
        GatewayNotifier.cancelAlerts(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (environment.startOnBoot && gatewayServer?.isRunning == true) {
            Log.i(TAG, "Task removed while running; scheduling restart")
            GatewayStartHelper.scheduleBootFallbacks(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        httpHealthCheckPosted = false
        streamHealthWatchdog?.stop()
        streamHealthWatchdog = null
        runCatching {
            (application as? GatewayApp)?.eventStreamHealthMonitor?.stop()
        }
        if (LowRamTvDevice.needsMemoryLite(this)) {
            FireMemoryGuard.uninstall()
        }
        gatewayServer?.stop()
        gatewayServer = null
        (application as? GatewayApp)?.activeDaddyLiveClient = null
        environment.serverRunning = false
        isServiceActive = false
        controlPortServer?.stop()
        controlPortServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    /** When TiviMate watch is on, nudge gateway if TiviMate becomes active while we're up. */
    private fun scheduleTiviMateResumeWatch() {
        if (tiviMateWatchPosted) return
        tiviMateWatchPosted = true
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isServiceActive) {
                    tiviMateWatchPosted = false
                    return
                }
                if (environment.tivimateWatchEnabled && TiviMateWatch.isTiviMateLikelyActive(this@ServerService)) {
                    if (!GatewayStartHelper.isGatewayHealthy(this@ServerService)) {
                        Log.i(TAG, "TiviMate active; ensuring gateway health")
                        GatewayStartHelper.startIfNeeded(this@ServerService, "TiviMateWatch", allowReschedule = false)
                    }
                }
                mainHandler.postDelayed(this, TIVIMATE_WATCH_MS)
            }
        }, TIVIMATE_WATCH_MS)
    }

    /** Local self-check: FGS can survive while CIO HTTP engine is down. */
    private fun scheduleHttpHealthCheck() {
        if (httpHealthCheckPosted) return
        httpHealthCheckPosted = true
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isServiceActive) {
                    httpHealthCheckPosted = false
                    return
                }
                if (gatewayServer?.isRunning != true && !startInFlight) {
                    Log.w(TAG, "HTTP server not listening; restarting gateway block")
                    startGateway(skipReadySurface = true)
                }
                mainHandler.postDelayed(this, HTTP_HEALTH_CHECK_MS)
            }
        }, HTTP_HEALTH_CHECK_MS)
    }

    private fun restartGatewayAfterFailure() {
        if (::daddyLiveClient.isInitialized && daddyLiveClient.shouldSuppressRestartForOutage()) {
            Log.w(TAG, "Suppressing gateway restart during upstream outage/cache-serve mode")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                Log.w(TAG, "Restarting gateway after persistent upstream failures")
                streamHealthWatchdog?.stop()
                streamHealthWatchdog = null
                gatewayServer?.stop()
                gatewayServer = null
                if (::daddyLiveClient.isInitialized) {
                    daddyLiveClient.invalidateStaleCaches()
                    daddyLiveClient.scheduleChannelRefresh(force = true)
                }
            }
            startGateway(skipReadySurface = true)
        }
    }

    companion object {
        private const val TAG = "ServerService"
        @Volatile
        var isServiceActive: Boolean = false
            private set

        @Volatile
        var lastKnownChannelCount: Int = 0

        private val fireHeavyWorkScheduled = AtomicBoolean(false)

        const val ACTION_STOP = "com.thothassistant.stepdaddy.gateway.action.STOP"
        const val ACTION_ENSURE_GATEWAY = "com.thothassistant.stepdaddy.gateway.action.ENSURE_GATEWAY"
        const val ACTION_ENSURE_READY = "com.thothassistant.stepdaddy.gateway.action.ENSURE_READY"
        const val ACTION_REFRESH_SUPPLEMENTS =
            "com.thothassistant.stepdaddy.gateway.action.REFRESH_SUPPLEMENTS"
        private const val HTTP_HEALTH_CHECK_MS = 30_000L
        /** FUSA sticks need >25s when iptv-org CSV + supplement stores load on cold process. */
        private const val COMPONENT_INIT_MAX_WAIT_MS = 60_000L
        private const val COMPONENT_INIT_RETRY_MS = 5_000L
        private const val TIVIMATE_WATCH_MS = 60_000L
        private const val BOOT_CHANNEL_LOAD_MAX_WAIT_MS = 4_000L
        private const val BOOT_CHANNEL_REFRESH_DEFER_MS = 45_000L
        /** Fire Stick: catalog-ready HUD only; no network refresh in the LMK window. */
        private const val FIRE_BOOT_CHANNEL_REFRESH_DEFER_MS = 30_000L
        private const val FIRE_EMPTY_CHANNEL_REFRESH_DEFER_MS = 20_000L
        private const val FIRE_SUPPLEMENT_NETWORK_GAP_MS = 15_000L
        /** Fire Stick: EPG/supplement periodic work only after continuous survival window. */
        private const val FIRE_HEAVY_WORK_DEFER_MS = 360_000L
        /** Onn (~1.4 GiB): shorter settle before supplement sync — Fire timing unchanged. */
        private const val ONN_HEAVY_WORK_DEFER_MS = 90_000L
        private const val ONN_BOOT_CHANNEL_REFRESH_DEFER_MS = 15_000L
        private const val ONN_EMPTY_CHANNEL_REFRESH_DEFER_MS = 12_000L
        private const val ONN_SUPPLEMENT_NETWORK_GAP_MS = 5_000L
        private const val BOOT_EPG_BUILD_DEFER_MS = 5_000L
    }
}
