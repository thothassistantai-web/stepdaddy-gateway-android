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
        var streamFailures = 0
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
                streamFailures++
                client.noteStreamFailure(channelId, exc)
                Log.w(TAG, "Probe failed channel $channelId: ${exc.message}")
            }
        }
        val mirrorsOk = client.probeMirrors()
        if (streamFailures == 0) {
            consecutiveProbeFailures = 0
            if (!mirrorsOk) {
                Log.w(TAG, "Mirror probe failed but loopback streams OK; not counting toward restart")
                client.recordHealingAction("probe_mirror_only_fail")
            } else {
                client.recordHealingAction("probe_ok")
            }
            return
        }
        if (!mirrorsOk) {
            Log.w(TAG, "Mirror probe failed alongside stream failures")
        }
        if (client.shouldSuppressRestartForOutage()) {
            client.recordHealingAction("outage_mode_restart_suppressed")
            consecutiveProbeFailures = 0
            Log.w(TAG, "Suppressing restart while upstream outage/cache-serve mode is active")
            return
        }
        consecutiveProbeFailures++
        client.recordHealingAction("probe_fail streams=$streamFailures mirrors=$mirrorsOk")
        if (consecutiveProbeFailures >= GatewayConfig.WATCHDOG_RESTART_THRESHOLD) {
            Log.e(TAG, "Persistent probe failures ($consecutiveProbeFailures); requesting gateway restart")
            client.recordHealingAction("restart_requested")
            consecutiveProbeFailures = 0
            onPersistentFailure()
        }
    }

    companion object {
        private const val TAG = "StreamHealth"
    }
}
