package com.thothassistant.stepdaddy.gateway.relay

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VodCatalogRelayManager(
    context: Context,
    private val environment: GatewayEnvironment,
    private val repository: VodCatalogRelayRepository = VodCatalogRelayRepository(context),
    private val probe: VodCatalogRelayProbe = VodCatalogRelayProbe(context),
) {
    private val mutex = Mutex()
    private val listeners = CopyOnWriteArrayList<(VodCatalogRelayStatus) -> Unit>()

    fun addListener(listener: (VodCatalogRelayStatus) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener(currentStatus())
    }

    fun removeListener(listener: (VodCatalogRelayStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun currentStatus(): VodCatalogRelayStatus = VodCatalogRelayRuntime.status()

    fun applyCachedIfPresent(): VodCatalogRelayStatus {
        if (!environment.vodCatalogRelayEnabled) {
            VodCatalogRelayRuntime.clear()
            notifyListeners()
            return currentStatus()
        }
        val cached = repository.loadCache() ?: return currentStatus()
        val validated = VodCatalogRelayValidator.validate(
            manifest = cached.manifest,
            installedVersionName = BuildConfig.VERSION_NAME,
            cachedVersion = 0,
        ).getOrElse {
            Log.w(TAG, "Ignoring invalid vod-catalog-relay cache: ${it.message}")
            return currentStatus()
        }
        // Cache apply: keep titles; streams re-probed on next refresh.
        VodCatalogRelayRuntime.apply(
            manifest = validated,
            sourceLabel = cached.sourceLabel,
            fetchedAtMs = cached.fetchedAtMs,
            movieStreams = emptyMap(),
            showStreams = emptyMap(),
            probed = 0,
            probeOk = 0,
            deadPruned = 0,
        )
        notifyListeners()
        return currentStatus()
    }

    suspend fun refresh(reason: String = "manual", probeStreams: Boolean = true): Result<VodCatalogRelayStatus> =
        withContext(Dispatchers.IO) {
            if (!environment.vodCatalogRelayEnabled) {
                VodCatalogRelayRuntime.clear()
                notifyListeners()
                return@withContext Result.success(currentStatus())
            }
            mutex.withLock {
                val cachedVersion = VodCatalogRelayRuntime.version.takeIf { it > 0 }
                    ?: repository.loadCache()?.manifest?.version
                    ?: 0
                val fetch = repository.fetchRemote(cachedVersion = 0)
                fetch.fold(
                    onSuccess = { result ->
                        val validated = VodCatalogRelayValidator.validate(
                            manifest = result.manifest,
                            installedVersionName = BuildConfig.VERSION_NAME,
                            cachedVersion = 0,
                        ).getOrElse { return@withLock Result.failure(it) }
                        if (validated.version < cachedVersion) {
                            Log.i(TAG, "vod-catalog-relay skipped older v${validated.version} ($reason)")
                            return@withLock Result.success(currentStatus())
                        }
                        val deduped = VodCatalogRelayMerge.dedupeManifest(validated)
                        val probeOutcome = if (probeStreams &&
                            (deduped.movies.any { it.streams.isNotEmpty() } ||
                                deduped.shows.any { it.streams.isNotEmpty() })
                        ) {
                            probe.probeManifest(deduped)
                        } else {
                            VodCatalogRelayProbe.ProbeOutcome(
                                movieStreams = emptyMap(),
                                showStreams = emptyMap(),
                                probed = 0,
                                probeOk = 0,
                                deadPruned = 0,
                            )
                        }
                        val fetchedAt = System.currentTimeMillis()
                        VodCatalogRelayRuntime.apply(
                            manifest = deduped,
                            sourceLabel = result.sourceLabel,
                            fetchedAtMs = fetchedAt,
                            movieStreams = probeOutcome.movieStreams,
                            showStreams = probeOutcome.showStreams,
                            probed = probeOutcome.probed,
                            probeOk = probeOutcome.probeOk,
                            deadPruned = probeOutcome.deadPruned,
                        )
                        repository.saveCache(deduped, result.sourceLabel, fetchedAt)
                        Log.i(
                            TAG,
                            "vod-catalog-relay applied v${deduped.version} " +
                                "movies=${deduped.movies.size} shows=${deduped.shows.size} " +
                                "probeOk=${probeOutcome.probeOk}/${probeOutcome.probed} ($reason)",
                        )
                        notifyListeners()
                        Result.success(currentStatus())
                    },
                    onFailure = { err ->
                        Log.w(TAG, "vod-catalog-relay fetch failed ($reason): ${err.message}")
                        if (!VodCatalogRelayRuntime.isApplied) {
                            applyCachedIfPresent()
                        }
                        Result.failure(err)
                    },
                )
            }
        }

    private fun notifyListeners() {
        val status = currentStatus()
        listeners.forEach { runCatching { it(status) } }
    }

    companion object {
        private const val TAG = "VodCatalogRelay"
    }
}
