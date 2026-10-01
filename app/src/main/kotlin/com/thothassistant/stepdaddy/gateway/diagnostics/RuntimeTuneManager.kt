package com.thothassistant.stepdaddy.gateway.diagnostics

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Silent shared-pack pull for runtime tune (timeouts/concurrency). APK builds stay on OTA.
 */
class RuntimeTuneManager(
    context: Context,
    private val repository: RuntimeTuneRepository = RuntimeTuneRepository(context),
) {
    private val mutex = Mutex()

    fun applyCachedIfPresent(): RuntimeTuneStatus {
        val cached = repository.loadCache() ?: return RuntimeTuneRuntime.status()
        if (!passesMinApp(cached.pack)) {
            Log.w(TAG, "Ignoring runtime-tune cache (minAppVersion)")
            return RuntimeTuneRuntime.status()
        }
        RuntimeTuneRuntime.apply(cached.pack, cached.sourceLabel, cached.fetchedAtMs)
        return RuntimeTuneRuntime.status()
    }

    suspend fun refresh(reason: String = "manual"): Result<RuntimeTuneStatus> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cachedVersion = RuntimeTuneRuntime.version.takeIf { it > 0 }
                ?: repository.loadCache()?.pack?.version
                ?: 0
            repository.fetchRemote().fold(
                onSuccess = { result ->
                    if (!passesMinApp(result.pack)) {
                        return@withLock Result.failure(IllegalStateException("minAppVersion not met"))
                    }
                    if (result.pack.version < cachedVersion) {
                        Log.i(TAG, "runtime-tune refresh skipped ($reason): older v${result.pack.version}")
                        return@withLock Result.success(RuntimeTuneRuntime.status())
                    }
                    val fetchedAt = System.currentTimeMillis()
                    RuntimeTuneRuntime.apply(result.pack, result.sourceLabel, fetchedAt)
                    repository.saveCache(result.pack, result.sourceLabel, fetchedAt)
                    Log.i(TAG, "runtime-tune applied v${result.pack.version} from ${result.sourceLabel} ($reason)")
                    Result.success(RuntimeTuneRuntime.status())
                },
                onFailure = { err ->
                    Log.w(TAG, "runtime-tune fetch failed ($reason): ${err.message}")
                    if (!RuntimeTuneRuntime.isActive) applyCachedIfPresent()
                    Result.failure(err)
                },
            )
        }
    }

    private fun passesMinApp(pack: RuntimeTunePack): Boolean {
        val min = pack.minAppVersion?.trim().orEmpty()
        if (min.isEmpty()) return true
        return compareVersionNames(BuildConfig.VERSION_NAME, min) >= 0
    }

    companion object {
        private const val TAG = "RuntimeTuneMgr"

        /** Simple dotted numeric compare; non-numeric tails ignored. */
        fun compareVersionNames(installed: String, required: String): Int {
            fun parts(v: String) =
                v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(installed)
            val b = parts(required)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }
    }
}
