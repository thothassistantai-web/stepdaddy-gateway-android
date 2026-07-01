package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayMessageBus

/**
 * Single coordinator for ephemeral gateway feedback (replaces banner / heads-up / ServerReadyActivity).
 *
 * **Surfacing rules**
 * - Dashboard open → in-app HUD chip above the footer ([Host]).
 * - Another app / home → compact bottom overlay when draw-over permission is granted.
 * - Otherwise → no popup for success paths; ongoing FGS notification carries status.
 * - Errors → overlay, notification, or toast (in that order of preference).
 *
 * **Lifecycle**
 * - One primary "ready" surface per boot when the catalog first has channels.
 * - Deferred channel refresh only updates the message log unless the catalog grows substantially.
 */
object GatewayHud {
    private const val TAG = "GatewayHud"
    private const val SHORT_MS = 3_500L
    private const val LONG_MS = 5_500L
    private const val CATALOG_GROWTH_THRESHOLD = 250

    interface Host {
        fun show(message: String, durationMs: Long)
        fun dismiss()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var host: Host? = null

    @Volatile
    private var readySurfacedThisBoot = false

    @Volatile
    private var startingSurfaced = false

    @Volatile
    private var suppressReadyForCrashRecovery = false

    @Volatile
    private var lastReadyChannelCount = 0

    @Volatile
    private var tivimateLaunchedThisBoot = false

    fun initForService(environment: GatewayEnvironment) {
        readySurfacedThisBoot = environment.readyHudShownThisBoot
        suppressReadyForCrashRecovery = environment.isRecentCrashRecovery()
        if (readySurfacedThisBoot) {
            lastReadyChannelCount = ServerService.lastKnownChannelCount
        }
    }

    fun attachHost(h: Host) {
        host = h
    }

    fun detachHost(h: Host) {
        if (host === h) {
            host = null
        }
    }

    /** HTTP server is listening; launch TiViMate in parallel when enabled. */
    fun onHttpListening(context: Context, channelCount: Int, skipSurface: Boolean) {
        if (skipSurface) return
        val environment = (context.applicationContext as GatewayApp).gatewayEnvironment
        if (channelCount > 0) {
            onCatalogReady(context, channelCount, environment, launchTivimate = true)
            return
        }
        if (environment.autoLaunchTiviMate) {
            maybeLaunchTivimate(context, environment)
        }
        if (startingSurfaced) return
        startingSurfaced = true
        surface(
            context = context,
            message = context.getString(R.string.hud_starting),
            durationMs = SHORT_MS,
            kind = Kind.STARTING,
        )
    }

    /** Channel catalog has a non-zero count (initial load or deferred boot refresh). */
    fun onCatalogReady(
        context: Context,
        channelCount: Int,
        environment: GatewayEnvironment,
        launchTivimate: Boolean = false,
    ) {
        if (channelCount <= 0) return

        if (!readySurfacedThisBoot && !suppressReadyForCrashRecovery) {
            readySurfacedThisBoot = true
            environment.readyHudShownThisBoot = true
            lastReadyChannelCount = channelCount
            val message = readyMessage(context, channelCount)
            surface(context, message, LONG_MS, Kind.READY)
            GatewayMessageBus.postReady(environment.loopbackBase())
            if (launchTivimate) {
                maybeLaunchTivimate(context, environment)
            }
            Log.i(TAG, "Ready surfaced ($channelCount channels)")
            return
        }

        if (channelCount > lastReadyChannelCount + CATALOG_GROWTH_THRESHOLD) {
            lastReadyChannelCount = channelCount
            GatewayMessageBus.post(
                context.getString(R.string.hud_catalog_updated_log, channelCount),
                "STATUS",
            )
            if (host != null) {
                surface(
                    context,
                    context.getString(R.string.hud_catalog_updated, channelCount),
                    SHORT_MS,
                    Kind.IN_APP_ONLY,
                )
            }
        }
    }

    fun onFailed(context: Context, errorMessage: String) {
        val message = context.getString(R.string.hud_failed, errorMessage)
        surface(context, message, LONG_MS, Kind.ERROR, forceGlobal = true)
        GatewayNotifier.showServerFailedAlert(context, errorMessage)
    }

    fun readyMessage(context: Context, channelCount: Int): String =
        if (channelCount > 0) {
            context.getString(R.string.hud_ready, channelCount)
        } else {
            context.getString(R.string.hud_ready_loading)
        }

    private enum class Kind {
        STARTING,
        READY,
        ERROR,
        IN_APP_ONLY,
    }

    private fun surface(
        context: Context,
        message: String,
        durationMs: Long,
        kind: Kind,
        forceGlobal: Boolean = false,
    ) {
        mainHandler.post {
            val inApp = host
            when {
                inApp != null && kind != Kind.ERROR -> {
                    inApp.show(message, durationMs)
                }
                kind == Kind.IN_APP_ONLY -> Unit
                GatewayOverlay.canDraw(context) -> {
                    GatewayOverlay.showToast(context, message, durationMs)
                }
                kind == Kind.ERROR -> {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                }
                forceGlobal -> {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                }
                else -> Log.d(TAG, "Quiet ($kind): $message")
            }
        }
    }

    private fun maybeLaunchTivimate(context: Context, environment: GatewayEnvironment) {
        if (!environment.autoLaunchTiviMate || tivimateLaunchedThisBoot) return
        if (!TiviMateLauncher.isInstalled(context)) {
            Log.i(TAG, "Launch TiviMate skipped — not installed")
            return
        }
        tivimateLaunchedThisBoot = true
        val bootChannel = environment.tivimateBootTuneChannel
        mainHandler.postDelayed({
            TiviMateLauncher.launchForGateway(
                context,
                environment.loopbackBase(),
                environment.xtreamUsername,
                environment.xtreamPassword,
            )
            if (bootChannel > 0) {
                scheduleBootTuneWhenPatchReady(context, bootChannel)
            }
        }, LAUNCHER_SETTLE_MS)
    }

    private fun scheduleBootTuneWhenPatchReady(context: Context, channel: Int) {
        val appContext = context.applicationContext
        var attempts = 0
        val retry = object : Runnable {
            override fun run() {
                if (attempts++ >= 24) return
                val state = TiviMateController.probeState()
                if (!state.reachable) {
                    mainHandler.postDelayed(this, 500L)
                    return
                }
                val player = state.state
                if (TiviMatePlaylistStateHelper.shouldDeferBootTune(player)) {
                    Log.i(
                        TAG,
                        "Boot-tune deferred — ${player?.stateReason ?: "no_state"} " +
                            "(channels=${player?.channelCount ?: 0})",
                    )
                    mainHandler.postDelayed(this, 500L)
                    return
                }
                if (player?.setupDone == true && (player.channelCount ?: 0) > 0) {
                    Log.i(TAG, "Boot-tune skipped — playlist ready; TiViMate restores last channel")
                    return
                }
                if (TiviMateController.setBootTuneChannel(appContext, channel)) {
                    Log.i(TAG, "Boot-tune $channel saved via patch HTTP (first setup)")
                    return
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
        mainHandler.postDelayed(retry, 800L)
    }

    /** Wait for TiViMate cold start before parallel launch + boot-tune HTTP. */
    private const val LAUNCHER_SETTLE_MS = 2_500L
}
