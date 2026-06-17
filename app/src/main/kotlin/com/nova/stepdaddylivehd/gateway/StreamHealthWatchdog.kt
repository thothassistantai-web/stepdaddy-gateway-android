package com.nova.stepdaddylivehd.gateway

import android.util.Log
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class StreamHealthWatchdog(
    private val client: DaddyLiveClient,
    private val environment: GatewayEnvironment,
    private val onPersistentFailure: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var consecutiveProbeFailures = 0

    fun start() {
        scope.launch {
            delay(GatewayConfig.WATCHDOG_INITIAL_DELAY_MS)
            while (isActive) {
                runProbeCycle()
                delay(GatewayConfig.WATCHDOG_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun runProbeCycle() {
        var failures = 0
        for (channelId in GatewayConfig.WATCHDOG_PROBE_CHANNEL_IDS) {
            try {
                withTimeout(GatewayConfig.WATCHDOG_PROBE_TIMEOUT_MS) {
                    client.resolveStream(
                        channelId,
                        useProxy = true,
                        apiUrl = environment.loopbackBase(),
                    )
                }
                client.noteStreamSuccess(channelId)
                Log.d(TAG, "Probe OK channel $channelId")
            } catch (exc: Exception) {
                failures++
                client.noteStreamFailure(channelId, exc)
                Log.w(TAG, "Probe failed channel $channelId: ${exc.message}")
            }
        }
        val mirrorsOk = client.probeMirrors()
        if (!mirrorsOk) {
            failures++
            Log.w(TAG, "Mirror probe failed")
        }
        if (failures == 0) {
            consecutiveProbeFailures = 0
            client.recordHealingAction("probe_ok")
        } else {
            consecutiveProbeFailures++
            client.recordHealingAction("probe_fail count=$failures")
            client.invalidateStaleCaches()
            if (consecutiveProbeFailures >= GatewayConfig.WATCHDOG_RESTART_THRESHOLD) {
                Log.e(TAG, "Persistent probe failures ($consecutiveProbeFailures); requesting gateway restart")
                client.recordHealingAction("restart_requested")
                consecutiveProbeFailures = 0
                onPersistentFailure()
            }
        }
    }

    companion object {
        private const val TAG = "StreamHealth"
    }
}
