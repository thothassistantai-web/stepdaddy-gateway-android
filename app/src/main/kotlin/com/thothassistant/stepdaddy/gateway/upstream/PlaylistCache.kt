package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the generated TiviMate M3U so playlist refresh returns quickly instead of
 * rebuilding ~4k channels on every HTTP request (which wedges TiviMate "update all").
 */
class PlaylistCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var snapshot: Snapshot? = null

    private var buildFlight: BuildFlight? = null

    private data class Snapshot(
        val key: Long,
        val body: String,
        val builtAtMs: Long,
    )

    private data class BuildFlight(
        val key: Long,
        val deferred: Deferred<String>,
    )

    fun computeKey(
        channelCount: Int,
        supplementCount: Int,
        supplementSyncedAtMs: Long,
        channelRevision: Int,
        logoDbLoaded: Boolean,
    ): Long {
        var key = channelCount.toLong()
        key = key * 31 + supplementCount
        key = key * 31 + supplementSyncedAtMs
        key = key * 31 + channelRevision
        key = key * 31 + if (logoDbLoaded) 1 else 0
        return key
    }

    suspend fun getOrBuild(key: Long, builder: () -> String): String {
        snapshot?.takeIf { it.key == key }?.let { return it.body }

        val deferred = mutex.withLock {
            snapshot?.takeIf { it.key == key }?.let { return it.body }
            val existing = buildFlight
            if (existing?.key == key) {
                return@withLock existing.deferred
            }
            val flight = scope.async {
                val started = System.currentTimeMillis()
                val body = builder()
                mutex.withLock {
                    snapshot = Snapshot(key = key, body = body, builtAtMs = System.currentTimeMillis())
                    if (buildFlight?.key == key) {
                        buildFlight = null
                    }
                }
                Log.i(
                    TAG,
                    "Playlist cache built: ${body.length} bytes in ${System.currentTimeMillis() - started}ms",
                )
                body
            }
            buildFlight = BuildFlight(key = key, deferred = flight)
            flight
        }
        return deferred.await()
    }

    fun invalidate() {
        snapshot = null
    }

    fun schedulePrewarm(key: Long, builder: () -> String) {
        if (snapshot?.key == key) return
        scope.launch {
            runCatching { getOrBuild(key, builder) }
                .onFailure { exc -> Log.w(TAG, "Playlist prewarm failed", exc) }
        }
    }

    fun ageSeconds(): Long? {
        val builtAt = snapshot?.builtAtMs ?: return null
        return (System.currentTimeMillis() - builtAt) / 1000
    }

    companion object {
        private const val TAG = "PlaylistCache"
    }
}
