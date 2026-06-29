package com.thothassistant.stepdaddy.gateway

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.model.TivimateSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object GatewayHealth {
    private const val TAG = "GatewayHealth"
    private const val CONNECT_TIMEOUT_SEC = 5L
    private const val READ_TIMEOUT_SEC = 12L
    private const val STABLE_PROBE_HITS = 2

    enum class ReadinessPhase {
        COLD,
        WAKING,
        WAITING_CHANNELS,
        READY,
        TIMEOUT,
    }

    data class ReadinessSnapshot(
        val healthOk: Boolean,
        val starting: Boolean,
        val channelCount: Int,
        val setupPlaylistUrl: String?,
        val ready: Boolean,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val readinessPhase = AtomicReference(ReadinessPhase.COLD)

    fun readinessPhase(): ReadinessPhase = readinessPhase.get()

    fun setReadinessPhase(phase: ReadinessPhase) {
        readinessPhase.set(phase)
    }

    /** True when loopback /health returns JSON with "ok":true. Safe on any thread (OkHttp). */
    fun probeLoopback(context: android.content.Context): Boolean {
        val environment = (context.applicationContext as GatewayApp).gatewayEnvironment
        return probeReadiness(environment.loopbackBase()).ready
    }

    /** Suspend variant for coroutine callers — always probes on [Dispatchers.IO]. */
    suspend fun probeLoopbackAsync(context: android.content.Context): Boolean =
        withContext(Dispatchers.IO) {
            probeLoopback(context)
        }

    /** Probe [baseUrl] for channel catalog + setup payload. Safe on any thread. */
    fun probeReadiness(baseUrl: String): ReadinessSnapshot {
        val normalized = baseUrl.trim().trimEnd('/')
        val health = fetchHealth("$normalized/health?lite=1")
        val setup = fetchSetup("$normalized/tivimate-setup")
        val channelCount = totalChannelCount(health)
        val playlistUrl = setup?.playlist?.takeIf { it.isNotBlank() }
        val healthOk = health?.ok == true
        val starting = health?.starting == true || channelCount == 0
        val ready = healthOk && !starting && channelCount > 0 && !playlistUrl.isNullOrBlank()
        return ReadinessSnapshot(
            healthOk = healthOk,
            starting = starting,
            channelCount = channelCount,
            setupPlaylistUrl = playlistUrl,
            ready = ready,
        )
    }

    fun probeReadiness(context: android.content.Context): ReadinessSnapshot {
        val environment = (context.applicationContext as GatewayApp).gatewayEnvironment
        return probeReadiness(environment.loopbackBase())
    }

    /**
     * Two consecutive stable channel probes — HTTP may respond before upstream catalog is loaded.
     */
    fun isStableReady(snapshot: ReadinessSnapshot, previousChannelCount: Int, stableHits: Int): Boolean {
        if (!snapshot.ready) return false
        if (previousChannelCount < 0) return false
        return snapshot.channelCount == previousChannelCount &&
            stableHits + 1 >= STABLE_PROBE_HITS
    }

    private fun totalChannelCount(health: HealthResponse?): Int {
        if (health == null) return 0
        return health.channels + health.supplementChannels
    }

    private fun fetchHealth(url: String): HealthResponse? =
        runCatching {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                json.decodeFromString<HealthResponse>(body)
            }
        }.onFailure { exc ->
            Log.d(TAG, "Health probe failed ($url): ${exc.message}")
        }.getOrNull()

    private fun fetchSetup(url: String): TivimateSetup? =
        runCatching {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                json.decodeFromString<TivimateSetup>(body)
            }
        }.onFailure { exc ->
            Log.d(TAG, "Setup probe failed ($url): ${exc.message}")
        }.getOrNull()
}
