package com.nova.stepdaddylivehd.gateway

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
import com.nova.stepdaddylivehd.gateway.ui.MainActivity
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerService : LifecycleService() {
    private lateinit var environment: GatewayEnvironment
    private lateinit var daddyLiveClient: DaddyLiveClient
    private lateinit var epgManager: com.nova.stepdaddylivehd.gateway.epg.EpgManager
    private var gatewayServer: GatewayServer? = null
    private var streamHealthWatchdog: StreamHealthWatchdog? = null
    private val startMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var startInFlight = false
    @Volatile
    private var readyBannerShown = false
    private var skipBannerForCrashRecovery = false
    private var httpHealthCheckPosted = false

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
        scheduleHttpHealthCheck()
        this.epgManager = app.epgManager
        // Kick HTTP on IO — DaddyLiveClient disk parse must not block main (boot ANR).
        startGateway()
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
                    val client = ensureDaddyLiveClient(app)
                    gatewayServer = GatewayServer(
                        this@ServerService,
                        environment,
                        client,
                        epgManager,
                        app.logoResolver,
                        app.channelMetaStore,
                    ).also { it.start() }
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
                    epgManager.schedulePeriodicRefresh { daddyLiveClient.channels }
                    scheduleDeferredBootChannelRefresh(skipReadyBanner)
                } catch (exc: Exception) {
                    Log.e(TAG, "Failed to start gateway on port ${environment.port}", exc)
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

    private suspend fun ensureDaddyLiveClient(app: GatewayApp): DaddyLiveClient {
        if (::daddyLiveClient.isInitialized) {
            return daddyLiveClient
        }
        daddyLiveClient = DaddyLiveClient(
            environment,
            app.epgChannelMapper,
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
            delay(BOOT_CHANNEL_REFRESH_DEFER_MS)
            if (!isServiceActive || !::daddyLiveClient.isInitialized) return@launch
            val app = application as GatewayApp
            daddyLiveClient.scheduleChannelRefresh(force = true) {
                updateRunningNotification()
                daddyLiveClient.schedulePrewarmDelayed()
                app.logoResolver.schedulePrewarm(
                    daddyLiveClient.channels.map { it.name to it.tvgId },
                )
                if (!skipReadyBanner && !MainActivity.isInForeground) {
                    showReadyBanner(daddyLiveClient.channels.size)
                }
                if (!epgManager.epgReady()) {
                    epgManager.scheduleRefresh(daddyLiveClient.channels, force = true)
                }
            }
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

        const val ACTION_STOP = "com.nova.stepdaddylivehd.gateway.action.STOP"
        const val ACTION_ENSURE_GATEWAY = "com.nova.stepdaddylivehd.gateway.action.ENSURE_GATEWAY"
        private const val HTTP_HEALTH_CHECK_MS = 90_000L
        private const val BOOT_CHANNEL_REFRESH_DEFER_MS = 45_000L
        private const val REQUEST_CODE_SERVER_READY = 30_200
    }
}
