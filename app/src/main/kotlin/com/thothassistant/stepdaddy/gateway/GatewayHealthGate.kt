package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object GatewayHealthGate {
    private const val DEFAULT_MAX_WAIT_MS = 30_000L
    private const val DEFAULT_POLL_MS = 500L

    suspend fun awaitHealthy(
        context: Context,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        if (probeHealthy(context)) return true
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(pollMs)
            if (probeHealthy(context)) return true
        }
        return probeHealthy(context)
    }

    private suspend fun probeHealthy(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            GatewayStartHelper.isGatewayHealthy(context)
        }
}
