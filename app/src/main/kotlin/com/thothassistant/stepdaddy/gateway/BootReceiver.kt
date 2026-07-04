package com.thothassistant.stepdaddy.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "Ignoring LOCKED_BOOT_COMPLETED; wait for BOOT_COMPLETED")
            return
        }
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) {
            return
        }
        val pendingResult = goAsync()
        val wakeLock = acquireBootWakeLock(context)
        val appContext = context.applicationContext
        bootExecutor.execute {
            try {
                Log.i(TAG, "Boot received ($action); attempting gateway start")
                val environment = (appContext as GatewayApp).gatewayEnvironment
                environment.clearReadyBannerForNewBoot()
                environment.clearBootStaleState()
                ScreenWakeRegistrar.register(appContext)

                val fireTv = FireTvDevice.isFireTv(appContext)
                if (fireTv) {
                    // Arm keep-alive before any delay — Fire Stick LMK can kill FGS mid-boot.
                    GatewayStartHelper.scheduleFireBootFallbacks(appContext)
                    Log.i(TAG, "Fire TV boot path: delay ${FIRE_BOOT_DELAY_MS}ms for memory settle")
                    Thread.sleep(FIRE_BOOT_DELAY_MS)
                    waitForNetwork(appContext, FIRE_NETWORK_WAIT_MS)
                }

                val result = GatewayStartHelper.startIfNeeded(appContext, "BootReceiver", allowReschedule = false)
                Log.i(TAG, "BootReceiver start result: $result")
                when (result) {
                    GatewayStartHelper.StartResult.STARTED,
                    GatewayStartHelper.StartResult.ALREADY_RUNNING,
                    -> {
                        // Non-Fire: startIfNeeded / ServerService manage fallbacks.
                        // Fire: keep alarms until catalog is healthy (LMK kills FGS).
                        if (fireTv) {
                            GatewayStartHelper.scheduleFireBootFallbacks(appContext)
                        }
                    }
                    GatewayStartHelper.StartResult.LAUNCHED_TRAMPOLINE,
                    GatewayStartHelper.StartResult.SCHEDULED_FALLBACK,
                    -> Unit // fallbacks already scheduled inside startIfNeeded
                    else -> GatewayStartHelper.scheduleBootFallbacksAsync(appContext)
                }
                val retryDelayMs = if (fireTv) FIRE_BOOT_RETRY_DELAY_MS else BOOT_RETRY_DELAY_MS
                if (!ServerService.isServiceActive) {
                    Thread.sleep(retryDelayMs)
                    if (!ServerService.isServiceActive) {
                        val retry = GatewayStartHelper.startIfNeeded(
                            appContext,
                            "BootReceiver+retry",
                            allowReschedule = false,
                        )
                        Log.i(TAG, "BootReceiver retry result: $retry")
                    }
                }
                if (fireTv && !GatewayStartHelper.isGatewayHealthy(appContext)) {
                    GatewayStartHelper.scheduleFireBootFallbacks(appContext)
                }
            } finally {
                releaseBootWakeLock(wakeLock)
                pendingResult.finish()
            }
        }
    }

    private fun waitForNetwork(context: Context, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasUsableNetwork(context)) {
                Log.i(TAG, "Fire TV network ready")
                return
            }
            Thread.sleep(1_000L)
        }
        Log.w(TAG, "Fire TV network not ready after ${timeoutMs}ms; starting anyway")
    }

    @Suppress("DEPRECATION")
    private fun hasUsableNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    private fun acquireBootWakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val holdMs = if (FireTvDevice.isFireTv(context)) FIRE_BOOT_WAKE_LOCK_MS else BOOT_WAKE_LOCK_MS
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(holdMs)
            }
        }.getOrNull()

    private fun releaseBootWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val WAKE_LOCK_TAG = "StepDaddy::BootStart"
        private const val BOOT_WAKE_LOCK_MS = 60_000L
        /** Fire Stick needs a longer hold while delayed start + catalog load run. */
        private const val FIRE_BOOT_WAKE_LOCK_MS = 180_000L
        private const val BOOT_RETRY_DELAY_MS = 3_000L
        private const val FIRE_BOOT_RETRY_DELAY_MS = 8_000L
        /** Let Amazon launcher / other boot receivers finish and free RAM. */
        private const val FIRE_BOOT_DELAY_MS = 20_000L
        private const val FIRE_NETWORK_WAIT_MS = 30_000L
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private val bootExecutor = Executors.newSingleThreadExecutor()
    }
}
