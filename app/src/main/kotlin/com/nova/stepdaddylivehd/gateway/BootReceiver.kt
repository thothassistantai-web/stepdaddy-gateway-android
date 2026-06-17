package com.nova.stepdaddylivehd.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

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
        Handler(Looper.getMainLooper()).post {
            try {
                Log.i(TAG, "Boot received ($action); attempting gateway start")
                (appContext as GatewayApp).gatewayEnvironment.clearReadyBannerForNewBoot()
                val result = GatewayStartHelper.startIfNeeded(appContext, "BootReceiver", allowReschedule = false)
                Log.i(TAG, "BootReceiver start result: $result")
                when (result) {
                    GatewayStartHelper.StartResult.STARTED,
                    GatewayStartHelper.StartResult.ALREADY_RUNNING,
                    GatewayStartHelper.StartResult.LAUNCHED_TRAMPOLINE,
                    -> GatewayStartHelper.cancelBootFallbacks(appContext)
                    else -> GatewayStartHelper.scheduleBootFallbacksAsync(appContext)
                }
                if (!ServerService.isServiceActive) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!ServerService.isServiceActive) {
                            val retry = GatewayStartHelper.startIfNeeded(
                                appContext,
                                "BootReceiver+3s",
                                allowReschedule = false,
                            )
                            Log.i(TAG, "BootReceiver+3s retry result: $retry")
                        }
                    }, BOOT_RETRY_DELAY_MS)
                }
            } finally {
                releaseBootWakeLock(wakeLock)
                pendingResult.finish()
            }
        }
    }

    private fun acquireBootWakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(BOOT_WAKE_LOCK_MS)
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
        private const val BOOT_RETRY_DELAY_MS = 3_000L
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
