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
            !health.epgReady -> "Building EPG…"
            else -> "Serving playlist"
        }
}

class GatewayStatusMonitor(
    private val healthUrl: () -> String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): GatewayLiveStatus = withContext(Dispatchers.IO) {
        val url = healthUrl()
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
                    GatewayLiveStatus(health = health, lastFetchMs = System.currentTimeMillis())
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

    companion object {
        private const val TAG = "GatewayStatusMonitor"

        @Volatile
        var lastCachedStatus: GatewayLiveStatus? = null
            private set
    }
}
