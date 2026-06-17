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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerService : LifecycleService() {
    private lateinit var environment: GatewayEnvironment
    private lateinit var daddyLiveClient: DaddyLiveClient
    private lateinit var epgManager: com.nova.stepdaddylivehd.gateway.epg.EpgManager
    private var gatewayServer: GatewayServer? = null
    private val startMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var startInFlight = false
    @Volatile
    private var readyBannerShown = false

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        GatewayStartHelper.resetFallbacksScheduled()
        val app = application as GatewayApp
        environment = app.gatewayEnvironment
        val epgManager = app.epgManager
        this.epgManager = epgManager
        daddyLiveClient = DaddyLiveClient(environment, app.epgChannelMapper, context = this)
        daddyLiveClient.scheduleChannelRefresh(force = false)
        GatewayNotifier.createChannels(this)
        startForeground(
            GatewayNotifier.NOTIFICATION_ID_ONGOING,
            GatewayNotifier.buildOngoingNotification(
                this,
                GatewayNotifier.GatewayState.STARTING,
                environment.loopbackBase(),
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopGateway()
            else -> startGateway()
        }
        return START_STICKY
    }

    private fun startGateway() {
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
                    gatewayServer = GatewayServer(this@ServerService, environment, daddyLiveClient, epgManager)
                        .also { it.start() }
                    environment.serverRunning = true
                    val channelCount = daddyLiveClient.channels.size
                    mainHandler.post { showServerReadyIfBackground(channelCount) }
                    updateRunningNotification()
                    notifyForegroundIfVisible(R.string.toast_server_running)
                    epgManager.schedulePeriodicRefresh { daddyLiveClient.channels }
                    daddyLiveClient.scheduleChannelRefresh(force = true) {
                        updateRunningNotification()
                        if (!MainActivity.isInForeground) {
                            showReadyBanner(daddyLiveClient.channels.size)
                        }
                        if (!epgManager.epgReady()) {
                            epgManager.scheduleRefresh(daddyLiveClient.channels, force = true)
                        }
                    }
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

    private fun updateRunningNotification() {
        val channelCount = daddyLiveClient.channels.size
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
            if (readyBannerShown) return
            readyBannerShown = true
        }
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

    companion object {
        private const val TAG = "ServerService"
        private const val LAUNCHER_SETTLE_MS = 2_000L
        @Volatile
        var isServiceActive: Boolean = false
            private set

        @Volatile
        var lastKnownChannelCount: Int = 0

        const val ACTION_STOP = "com.nova.stepdaddylivehd.gateway.action.STOP"
        private const val REQUEST_CODE_SERVER_READY = 30_200
    }
}
