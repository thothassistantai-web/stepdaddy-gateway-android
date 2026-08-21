package com.thothassistant.stepdaddy.gateway.relay

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads cached relay on boot, refreshes from pinned GitHub URLs, applies overlays.
 */
class DomainRelayManager(
    context: Context,
    private val environment: GatewayEnvironment,
    private val repository: DomainRelayRepository = DomainRelayRepository(context),
) {
    private val mutex = Mutex()
    private val lastOutageFetchMs = AtomicLong(0L)
    private val listeners = CopyOnWriteArrayList<(DomainRelayStatus) -> Unit>()

    fun addListener(listener: (DomainRelayStatus) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener(currentStatus())
    }

    fun removeListener(listener: (DomainRelayStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun currentStatus(): DomainRelayStatus =
        DomainRelayRuntime.status(
            userCustomizedPrimary = environment.hasUserCustomizedDlhdBaseUrl,
            userCustomizedMirrors = environment.hasUserCustomizedMirrorUrls,
        )

    fun relayOverridesActive(): Boolean = currentStatus().active

    /** Apply last-good cache immediately (no network). Safe during early init. */
    fun applyCachedIfPresent(): DomainRelayStatus {
        val cached = repository.loadCache() ?: return currentStatus()
        val validated = DomainRelayValidator.validate(
            manifest = cached.manifest,
            installedVersionName = BuildConfig.VERSION_NAME,
            cachedVersion = 0,
        ).getOrElse {
            Log.w(TAG, "Ignoring invalid domain-relay cache: ${it.message}")
            return currentStatus()
        }
        DomainRelayRuntime.apply(validated, cached.sourceLabel, cached.fetchedAtMs)
        notifyListeners()
        return currentStatus()
    }

    suspend fun refresh(reason: String = "manual"): Result<DomainRelayStatus> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cachedVersion = DomainRelayRuntime.version.takeIf { it > 0 }
                ?: repository.loadCache()?.manifest?.version
                ?: 0
            val fetch = repository.fetchRemote(cachedVersion = 0)
            fetch.fold(
                onSuccess = { result ->
                    // Re-validate against cached version so older remote is skipped.
                    val validated = DomainRelayValidator.validate(
                        manifest = result.manifest,
                        installedVersionName = BuildConfig.VERSION_NAME,
                        cachedVersion = cachedVersion,
                    ).getOrElse { err ->
                        if (cachedVersion > 0 && DomainRelayRuntime.isActive) {
                            Log.i(TAG, "domain-relay refresh skipped ($reason): ${err.message}")
                            return@withLock Result.success(currentStatus())
                        }
                        // Allow equal version re-apply when nothing active yet.
                        val allowEqual = DomainRelayValidator.validate(
                            manifest = result.manifest,
                            installedVersionName = BuildConfig.VERSION_NAME,
                            cachedVersion = 0,
                        )
                        allowEqual.getOrElse { return@withLock Result.failure(err) }
                    }
                    val fetchedAt = System.currentTimeMillis()
                    DomainRelayRuntime.apply(validated, result.sourceLabel, fetchedAt)
                    repository.saveCache(validated, result.sourceLabel, fetchedAt)
                    Log.i(
                        TAG,
                        "domain-relay applied v${validated.version} from ${result.sourceLabel} ($reason)",
                    )
                    notifyListeners()
                    Result.success(currentStatus())
                },
                onFailure = { err ->
                    Log.w(TAG, "domain-relay fetch failed ($reason): ${err.message}")
                    if (!DomainRelayRuntime.isActive) {
                        applyCachedIfPresent()
                    }
                    Result.failure(err)
                },
            )
        }
    }

    /** Debounced refresh when upstream mirrors fail / outage opens. */
    suspend fun refreshOnOutage() {
        val now = System.currentTimeMillis()
        val previous = lastOutageFetchMs.get()
        if (now - previous < OUTAGE_DEBOUNCE_MS) return
        if (!lastOutageFetchMs.compareAndSet(previous, now)) return
        refresh(reason = "upstream-outage")
    }

    private fun notifyListeners() {
        val status = currentStatus()
        listeners.forEach { listener ->
            runCatching { listener(status) }
        }
    }

    companion object {
        private const val TAG = "DomainRelay"
        private const val OUTAGE_DEBOUNCE_MS = 120_000L
    }
}
