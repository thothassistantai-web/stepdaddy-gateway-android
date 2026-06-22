package com.thothassistant.stepdaddy.gateway

import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.sidecar.EmbeddedSidecarRepository
import com.thothassistant.stepdaddy.gateway.sidecar.EmbeddedSidecarServer
import com.thothassistant.stepdaddy.gateway.ui.MainActivity
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayDiagnostics
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayMessageBus
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.admin.GatewayAdminController
import com.thothassistant.stepdaddy.gateway.upstream.LogoBackfillService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerService : LifecycleService() {
    private lateinit var environment: GatewayEnvironment
    private lateinit var daddyLiveClient: DaddyLiveClient
    private lateinit var epgManager: com.thothassistant.stepdaddy.gateway.epg.EpgManager
    private var gatewayServer: GatewayServer? = null
    private var embeddedSidecarServer: EmbeddedSidecarServer? = null
    private var streamHealthWatchdog: StreamHealthWatchdog? = null
    private val startMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var startInFlight = false
    @Volatile
    private var readyBannerShown = false
    @Volatile
    private var tivimateLaunchedThisBoot = false
    private var skipBannerForCrashRecovery = false
    private var httpHealthCheckPosted = false
    private var tiviMateWatchPosted = false
    private var logoBackfillJob: Job? = null

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
        skipBannerForCrashRecovery = environment.isRecentCrashRecovery()
        environment.recordServiceStart()
        readyBannerShown = environment.readyBannerShownThisBoot
        GatewayStartHelper.cancelBootFallbacks(this)
        GatewayStartHelper.resetFallbacksScheduled()
        ScreenWakeRegistrar.register(this)
        GatewayMessageBus.postBoot("ServerService")
        GatewayDiagnostics.info(TAG, "Foreground service created")
        scheduleHttpHealthCheck()
        scheduleTiviMateResumeWatch()
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as GatewayApp
            app.awaitComponents()
            epgManager = app.epgManager
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopGateway()
            ACTION_ENSURE_GATEWAY -> ensureGatewayListening()
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
        startGateway(skipReadyBanner = true)
    }

    private fun startGateway(skipReadyBanner: Boolean = false) {
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
                    app.awaitComponents()
                    if (!::epgManager.isInitialized) {
                        epgManager = app.epgManager
                    }
                    startEmbeddedSidecar(app)
                    val client = ensureDaddyLiveClient(app)
                    client.awaitInitialLoad()
                    val adminController = GatewayAdminController(
                        context = this@ServerService,
                        environment = environment,
                        client = client,
                        epgManager = epgManager,
                        app = app,
                        logoResolver = app.logoResolver,
                        prewarmPlaylist = { gatewayServer?.prewarmPlaylist() },
                        runLogoBackfill = { runLogoBackfillNow() },
                        stopGatewayAction = { stopGateway() },
                        restartHttpAction = { restartGatewayAfterFailure() },
                        restartFullAction = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                startMutex.withLock {
                                    gatewayServer?.stop()
                                    gatewayServer = null
                                }
                                delay(500L)
                                startGateway(skipReadyBanner = true)
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
                    app.supplementSource.onRefreshComplete = {
                        val sync = app.supplementSource.syncSnapshot()
                        GatewayDiagnostics.info(
                            TAG,
                            buildString {
                                append("Supplement sync done")
                                append(" · MOJ ${sync.moveOnJoyChannels}")
                                append(" · sports ${sync.sportsChannels}")
                                if (sync.sportsEventsScanned > 0) {
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
                        server.prewarmPlaylist()
                        epgManager.scheduleRefresh(client.channels, force = true)
                        scheduleLogoBackfill()
                    }
                    server.start()
                    server.prewarmPlaylist()
                    environment.serverRunning = true
                    streamHealthWatchdog?.stop()
                    streamHealthWatchdog = StreamHealthWatchdog(
                        client = client,
                        environment = environment,
                        onPersistentFailure = { restartGatewayAfterFailure() },
                    ).also { it.start() }
                    client.reportHealthyStart()
                    val channelCount = client.channels.size
                    if (!skipReadyBanner) {
                        mainHandler.post { showServerReadyIfBackground(channelCount) }
                    }
                    updateRunningNotification()
                    GatewayStartHelper.schedulePeriodicEnsureAlive(this@ServerService)
                    notifyForegroundIfVisible(R.string.toast_server_running)
                    GatewayMessageBus.postReady(environment.loopbackBase())
                    GatewayDiagnostics.info(TAG, "Gateway listening on ${environment.loopbackBase()} ($channelCount channels)")
                    scheduleEmbeddedSidecarRefreshIfEmpty(app)
                    if (client.channels.isEmpty()) {
                        client.scheduleChannelRefresh(force = true) {
                            updateRunningNotification()
                        }
                    }
                    epgManager.schedulePeriodicRefresh { daddyLiveClient.channels }
                    if (epgManager.needsBuild()) {
                        epgManager.scheduleRefresh(
                            client.channels,
                            force = true,
                            tvtvGapFill = false,
                        )
                    }
                    app.supplementSource.schedulePeriodicRefresh { daddyLiveClient.channels }
                    scheduleLogoBackfill(deferMs = 8_000L)
                    scheduleDeferredBootChannelRefresh(skipReadyBanner)
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
                    GatewayNotifier.showServerFailedAlert(this@ServerService, message)
                    notifyForegroundIfVisible(R.string.toast_server_failed)
                } finally {
                    startInFlight = false
                }
            }
        }
    }

    /** Debounced fuzzy logo backfill — smallest playlist category first. */
    private fun scheduleLogoBackfill(deferMs: Long = 3_000L) {
        logoBackfillJob?.cancel()
        logoBackfillJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(deferMs)
            if (!isServiceActive || !::daddyLiveClient.isInitialized) return@launch
            runCatching { runLogoBackfillNow() }
                .onFailure { exc -> GatewayDiagnostics.error(TAG, "Logo backfill failed", exc) }
        }
    }

    private suspend fun runLogoBackfillNow(): LogoBackfillService.Result {
        val app = application as GatewayApp
        app.logoResolver.awaitLoaded()
        val result = LogoBackfillService(
            this@ServerService,
            app.logoResolver,
            app.channelMetaStore,
            environment.loopbackBase(),
        ).run(
            daddyLiveClient.channels,
            app.supplementSource.channels(),
        )
        if (result.assigned > 0) {
            GatewayDiagnostics.info(
                TAG,
                "Logo backfill assigned ${result.assigned}/${result.scanned} " +
                    "across ${result.groupsProcessed} groups",
            )
            gatewayServer?.prewarmPlaylist()
        }
        return result
    }

    private suspend fun startEmbeddedSidecar(app: GatewayApp) {
        if (!environment.embeddedSidecarEnabled) return
        if (embeddedSidecarServer?.isRunning == true) return
        environment.ensureEmbeddedSidecarUrl()
        app.embeddedSidecarRepository.schedulePeriodicRefresh()
        embeddedSidecarServer = EmbeddedSidecarServer(app.embeddedSidecarRepository).also { it.start() }
    }

    private fun scheduleEmbeddedSidecarRefreshIfEmpty(app: GatewayApp) {
        if (!environment.embeddedSidecarEnabled) return
        if (app.embeddedSidecarRepository.channelCount() > 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            app.embeddedSidecarRepository.refresh(force = true)
        }
    }

    private suspend fun ensureDaddyLiveClient(app: GatewayApp): DaddyLiveClient {
        if (::daddyLiveClient.isInitialized) {
            return daddyLiveClient
        }
        daddyLiveClient = DaddyLiveClient(
            environment,
            app.epgChannelMapper,
            app.tvgIdResolver,
            app.logoResolver,
            app.channelMetaStore,
            context = this,
        )
        return daddyLiveClient
    }

    /**
     * Serve disk-cached channels first; defer upstream refresh so boot-time CPU/network
     * stays available for HTTP listen + first playlist/stream requests.
     */
    private fun scheduleDeferredBootChannelRefresh(skipReadyBanner: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val deferMs =
                if (::daddyLiveClient.isInitialized && daddyLiveClient.channels.isEmpty()) {
                    8_000L
                } else {
                    BOOT_CHANNEL_REFRESH_DEFER_MS
                }
            delay(deferMs)
            if (!isServiceActive || !::daddyLiveClient.isInitialized) return@launch
            val app = application as GatewayApp
            daddyLiveClient.scheduleChannelRefresh(force = true) {
                updateRunningNotification()
                daddyLiveClient.schedulePrewarmDelayed()
                app.tvgIdResolver.backfillUnmapped(
                    this@ServerService,
                    app.epgChannelMapper,
                    daddyLiveClient.channels,
                )
                scheduleLogoBackfill(deferMs = 2_000L)
                app.logoResolver.schedulePrewarm(
                    daddyLiveClient.channels.map { it.name to it.tvgId },
                )
                if (!skipReadyBanner && !MainActivity.isInForeground) {
                    showReadyBanner(daddyLiveClient.channels.size)
                }
            }
            runCatching {
                app.supplementSource.refresh(
                    daddyLiveClient.channels,
                    force = true,
                    dlhdScheduleBaseUrl = daddyLiveClient.activeBaseUrl,
                )
            }.onFailure { exc ->
                Log.w(TAG, "Boot supplement refresh failed", exc)
            }
            epgManager.scheduleRefresh(daddyLiveClient.channels, force = true)
        }
    }

    private fun updateRunningNotification() {
        val channelCount =
            if (::daddyLiveClient.isInitialized) daddyLiveClient.channels.size else lastKnownChannelCount
        lastKnownChannelCount = channelCount
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
        gatewayServer?.stop()
        gatewayServer = null
        embeddedSidecarServer?.stop()
        embeddedSidecarServer = null
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
        gatewayServer?.stop()
        gatewayServer = null
        embeddedSidecarServer?.stop()
        embeddedSidecarServer = null
        environment.serverRunning = false
        isServiceActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun notifyForegroundIfVisible(messageRes: Int) {
        if (!MainActivity.isInForeground) return
        mainHandler.post {
            Toast.makeText(applicationContext, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showServerReadyIfBackground(channelCount: Int) {
        if (MainActivity.isInForeground) return
        lastKnownChannelCount = channelCount
        showReadyBanner(channelCount)
    }

    private fun showReadyBanner(channelCount: Int) {
        synchronized(this) {
            if (skipBannerForCrashRecovery || readyBannerShown || environment.readyBannerShownThisBoot) {
                return
            }
            readyBannerShown = true
            environment.readyBannerShownThisBoot = true
        }
        Log.i(TAG, "Showing ready banner (channels=$channelCount)")
        mainHandler.postDelayed({
            if (GatewayOverlay.canDraw(this)) {
                GatewayOverlay.showServerReady(this, channelCount)
                return@postDelayed
            }
            if (GatewayNotifier.shouldUseFullScreenStartedAlert(this)) {
                GatewayNotifier.showServerStartedAlert(this, channelCount)
                return@postDelayed
            }
            launchServerReadyActivity(channelCount)
        }, LAUNCHER_SETTLE_MS)
        maybeLaunchTivimate()
    }

    private fun maybeLaunchTivimate() {
        if (!environment.launchTivimateOnReady || tivimateLaunchedThisBoot) return
        if (!TiviMateLauncher.isInstalled(this)) {
            Log.i(TAG, "Launch TiviMate skipped — not installed")
            return
        }
        tivimateLaunchedThisBoot = true
        mainHandler.postDelayed({
            TiviMateLauncher.launch(this@ServerService)
        }, LAUNCHER_SETTLE_MS)
    }

    private fun launchServerReadyActivity(channelCount: Int) {
        val readyIntent = Intent(this, ServerReadyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ServerReadyActivity.EXTRA_CHANNEL_COUNT, channelCount)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_SERVER_READY,
                    readyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                pendingIntent.send(
                    this,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options.toBundle(),
                )
            } else {
                startActivity(readyIntent)
            }
            Log.i(TAG, "Launched server-ready activity (channels=$channelCount)")
        }.onFailure { exc ->
            Log.w(TAG, "Server-ready activity launch failed: ${exc.message}")
        }
    }

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
                    startGateway(skipReadyBanner = true)
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
            startGateway(skipReadyBanner = true)
        }
    }

    companion object {
        private const val TAG = "ServerService"
        private const val LAUNCHER_SETTLE_MS = 2_000L
        @Volatile
        var isServiceActive: Boolean = false
            private set

        @Volatile
        var lastKnownChannelCount: Int = 0

        const val ACTION_STOP = "com.thothassistant.stepdaddy.gateway.action.STOP"
        const val ACTION_ENSURE_GATEWAY = "com.thothassistant.stepdaddy.gateway.action.ENSURE_GATEWAY"
        private const val HTTP_HEALTH_CHECK_MS = 90_000L
        private const val TIVIMATE_WATCH_MS = 60_000L
        private const val BOOT_CHANNEL_REFRESH_DEFER_MS = 45_000L
        private const val REQUEST_CODE_SERVER_READY = 30_200
    }
}
