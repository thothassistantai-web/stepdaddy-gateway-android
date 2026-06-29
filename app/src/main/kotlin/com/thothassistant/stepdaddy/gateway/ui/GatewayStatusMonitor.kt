package com.thothassistant.stepdaddy.gateway.ui

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GatewayLiveStatus(
    val health: HealthResponse? = null,
    val fetchError: String? = null,
    val lastFetchMs: Long = 0L,
) {
    val isHealthy: Boolean get() = health?.ok == true && health.starting != true
    val activityLabel: String
        get() = when {
            fetchError != null -> "Error: $fetchError"
            health == null -> "Connecting…"
            health.starting -> "Loading channels…"
            health.healing?.breakerOpen == true -> "Circuit breaker open"
            health.healing?.outageMode == true -> "Upstream outage mode"
            health.healing?.cacheServeMode == true -> "Serving from cache"
            health.epgExternal -> "External EPG (TiviMate)"
            health.supplement?.specialEventsStale == true -> "Special Events scrape stale"
            health.ok && !health.starting && (health.channels > 0 || (health.providers?.total ?: 0) > 0) ->
                if (health.gatewayEpgEnabled && !health.epgReady) {
                    "Serving playlist · EPG building"
                } else {
                    "Serving playlist"
                }
            !health.epgReady -> "Building EPG…"
            else -> "Serving playlist"
        }
}

class GatewayStatusMonitor(
    private val healthUrl: () -> String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastFullHealth: HealthResponse? = null

    /**
     * @param requestFull when true, fetches full /health (categories, healing, coverage).
     *        Lite polls carry dashboard stats; expensive fields are merged from the last full snapshot.
     */
    suspend fun fetch(requestFull: Boolean = false): GatewayLiveStatus = withContext(Dispatchers.IO) {
        val base = healthUrl()
        val url = if (requestFull) {
            base
        } else {
            if (base.contains("?")) "$base&lite=1" else "$base?lite=1"
        }
        val result = runCatching {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    GatewayLiveStatus(
                        fetchError = "HTTP ${response.code}",
                        lastFetchMs = System.currentTimeMillis(),
                    )
                } else {
                    val health = json.decodeFromString<HealthResponse>(body)
                    val merged = if (requestFull) {
                        lastFullHealth = health
                        health
                    } else {
                        mergeDashboardHealth(health, lastFullHealth)
                    }
                    GatewayLiveStatus(health = merged, lastFetchMs = System.currentTimeMillis())
                }
            }
        }.getOrElse { exc ->
            Log.d(TAG, "Health fetch failed: ${exc.message}")
            GatewayLiveStatus(
                fetchError = exc.message ?: "unreachable",
                lastFetchMs = System.currentTimeMillis(),
            )
        }
        lastCachedStatus = result
        result
    }

    private fun mergeDashboardHealth(lite: HealthResponse, full: HealthResponse?): HealthResponse {
        if (full == null) return lite
        return lite.copy(
            topCategories = full.topCategories.ifEmpty { lite.topCategories },
            healing = full.healing ?: lite.healing,
            epgCoverage = full.epgCoverage ?: lite.epgCoverage,
            epgSourceCount = if (lite.epgSourceCount > 0) lite.epgSourceCount else full.epgSourceCount,
        )
    }

    companion object {
        private const val TAG = "GatewayStatusMonitor"

        @Volatile
        var lastCachedStatus: GatewayLiveStatus? = null
            private set
    }
}
