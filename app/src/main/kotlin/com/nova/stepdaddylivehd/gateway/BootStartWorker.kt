package com.nova.stepdaddylivehd.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BootStartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (ServerService.isServiceActive) {
            Log.i(TAG, "Server already active")
            return Result.success()
        }
        val result = GatewayStartHelper.startIfNeeded(applicationContext, "WorkManager", allowReschedule = false)
        return when (result) {
            GatewayStartHelper.StartResult.STARTED,
            GatewayStartHelper.StartResult.ALREADY_RUNNING,
            -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "BootStartWorker"
    }
}
