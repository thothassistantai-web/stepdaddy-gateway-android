package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic safety net when [GatewayEnvironment.startOnBoot] is enabled — recovers
 * from OEM kills / low-memory teardown without requiring the user to open StepDaddy.
 */
class GatewayEnsureAliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val environment = (appContext as GatewayApp).gatewayEnvironment
        if (!environment.startOnBoot) {
            GatewayStartHelper.cancelPeriodicEnsureAlive(appContext)
            return Result.success()
        }
        if (GatewayStartHelper.isGatewayHealthy(appContext)) {
            Log.i(TAG, "Gateway healthy; periodic check OK")
            return Result.success()
        }
        val source = if (environment.tivimateWatchEnabled && TiviMateWatch.isTiviMateLikelyActive(appContext)) {
            "PeriodicEnsureAlive+TiviMate"
        } else {
            "PeriodicEnsureAlive"
        }
        Log.i(TAG, "Gateway unhealthy; attempting recovery ($source)")
        val result = GatewayStartHelper.startIfNeeded(appContext, source, allowReschedule = false)
        return when (result) {
            GatewayStartHelper.StartResult.STARTED,
            GatewayStartHelper.StartResult.ALREADY_RUNNING,
            -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "GatewayEnsureAliveWorker"
    }
}
