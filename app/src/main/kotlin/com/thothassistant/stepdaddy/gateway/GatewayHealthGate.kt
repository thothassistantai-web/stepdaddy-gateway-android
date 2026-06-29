package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay

object GatewayHealthGate {
    private const val DEFAULT_MAX_WAIT_MS = 120_000L
    private const val DEFAULT_POLL_MS = 2_000L

    suspend fun awaitHealthy(
        context: Context,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean = GatewayStartHelper.ensureGatewayReady(context, maxWaitMs, pollMs)

    suspend fun awaitReady(
        context: Context,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean = awaitHealthy(context, maxWaitMs, pollMs)
}
