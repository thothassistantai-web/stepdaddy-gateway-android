package com.thothassistant.stepdaddy.gateway

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object GatewayHealth {
    private const val TAG = "GatewayHealth"
    private const val CONNECT_TIMEOUT_SEC = 5L
    private const val READ_TIMEOUT_SEC = 8L

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** True when loopback /health returns JSON with "ok":true. Safe on any thread (OkHttp). */
    fun probeLoopback(context: android.content.Context): Boolean {
        val environment = (context.applicationContext as GatewayApp).gatewayEnvironment
        val healthUrl = "${environment.loopbackBase()}/health"
        return runCatching {
            val request = Request.Builder().url(healthUrl).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                response.body?.string().orEmpty().contains("\"ok\"")
            }
        }.onFailure { exc ->
            Log.d(TAG, "Health probe failed ($healthUrl): ${exc.message}")
        }.getOrDefault(false)
    }

    /** Suspend variant for coroutine callers — always probes on [Dispatchers.IO]. */
    suspend fun probeLoopbackAsync(context: android.content.Context): Boolean =
        withContext(Dispatchers.IO) {
            probeLoopback(context)
        }
}
