package com.nova.stepdaddylivehd.gateway

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay

object GatewayHealthGate {
    private const val DEFAULT_MAX_WAIT_MS = 30_000L
    private const val DEFAULT_POLL_MS = 500L

    suspend fun awaitHealthy(
        context: Context,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        if (GatewayStartHelper.isGatewayHealthy(context)) return true
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(pollMs)
            if (GatewayStartHelper.isGatewayHealthy(context)) return true
        }
        return GatewayStartHelper.isGatewayHealthy(context)
    }
}
