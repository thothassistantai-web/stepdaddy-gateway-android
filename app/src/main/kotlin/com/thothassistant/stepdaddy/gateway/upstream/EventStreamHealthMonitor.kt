package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Periodic dlhd-event stream reachability monitor.
 *
 * Combines schedule lifecycle (including post-stop [DlhdEventStreamHealth.Status.ENDED])
 * with HTTP probes via [DlhdEventStreamProber]. Intended as a process-wide singleton
 * wired from [com.thothassistant.stepdaddy.gateway.GatewayApp] and started by
 * [com.thothassistant.stepdaddy.gateway.ServerService].
 */
class EventStreamHealthMonitor(
    private val channelProvider: () -> List<SupplementChannel>,
    private val store: DlhdEventStreamHealthStore,
    private val prober: DlhdEventStreamProber,
    private val sportsEnabled: () -> Boolean = { true },
    private val onStatesChanged: () -> Unit = {},
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null
    private var tvStreamProbe: (suspend (channelId: String) -> Boolean)? = null

    fun store(): DlhdEventStreamHealthStore = store

    fun state(token: String): DlhdEventStreamHealth.Status = store.status(token)

    fun isHealthy(token: String): Boolean = store.isHealthy(token)

    fun summary(activeStreams: Int): DlhdEventStreamHealth.Summary = store.summary(activeStreams)

    /** Start background probe sweeps; [tvStreamProbe] resolves numeric `tv|` event streams. */
    fun start(tvStreamProbe: suspend (channelId: String) -> Boolean) {
        probeJob?.cancel()
        this.tvStreamProbe = tvStreamProbe
        probeJob = scope.launch {
            delay(SupplementConfig.DLHD_EVENT_HEALTH_INITIAL_DELAY_MS)
            while (isActive) {
                if (sportsEnabled()) {
                    runProbeCycle()
                }
                delay(SupplementConfig.DLHD_EVENT_HEALTH_PROBE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        tvStreamProbe = null
    }

    suspend fun runProbeCycle() {
        val probe = tvStreamProbe
            ?: return
        val now = nowProvider()
        val events = channelProvider().filter { it.id.startsWith("dlhd-event:") }
        val activeTokens = events.map { it.id.removePrefix("dlhd-event:") }.toSet()
        val semaphore = Semaphore(SupplementConfig.DLHD_EVENT_HEALTH_MAX_CONCURRENT)
        val changed = AtomicBoolean(false)
        val jobs = events.map { event ->
            scope.async {
                semaphore.withPermit {
                    val token = event.id.removePrefix("dlhd-event:")
                    val before = store.status(token)
                    val result = probeEvent(event, probe, now)
                    if (result != null) {
                        store.record(token, result)
                    }
                    if (store.status(token) != before) {
                        changed.set(true)
                    }
                }
            }
        }
        jobs.awaitAll()
        val beforeRevision = store.revision()
        store.pruneTokens(activeTokens)
        if (changed.get() || store.revision() != beforeRevision) {
            runCatching {
                Log.i(TAG, "Event stream health updated (revision=${store.revision()})")
            }
            onStatesChanged()
        }
    }

    internal suspend fun probeEvent(
        event: SupplementChannel,
        tvStreamProbe: suspend (channelId: String) -> Boolean,
        now: Instant = nowProvider(),
    ): DlhdEventStreamHealth.ProbeResult? {
        when (SpecialEventLifecycle.visibilityForDlhdEvent(event, now)) {
            SpecialEventLifecycle.Visibility.ENDED_GRACE ->
                return DlhdEventStreamHealth.ProbeResult.ended()
            SpecialEventLifecycle.Visibility.EXPIRED ->
                return null
            SpecialEventLifecycle.Visibility.ACTIVE,
            null,
            -> Unit
        }
        if (!EventTitleHealthDots.isLiveStarted(event, now)) {
            return null
        }
        return prober.probe(event, tvStreamProbe)
    }

    companion object {
        private const val TAG = "EventStreamHealth"
    }
}
