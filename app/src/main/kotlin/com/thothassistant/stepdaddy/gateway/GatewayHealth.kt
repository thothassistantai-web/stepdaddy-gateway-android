package com.thothassistant.stepdaddy.gateway

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object GatewayHealth {
    private const val TAG = "GatewayHealth"
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 2_000

    /** True when loopback /health returns JSON with "ok":true. */
    fun probeLoopback(context: android.content.Context): Boolean {
        val environment = (context.applicationContext as GatewayApp).gatewayEnvironment
        val healthUrl = "${environment.loopbackBase()}/health"
        return runCatching {
            val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return false
                }
                connection.inputStream.bufferedReader().use { reader ->
                    reader.readText().contains("\"ok\"")
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { exc ->
            Log.d(TAG, "Health probe failed ($healthUrl): ${exc.message}")
        }.getOrDefault(false)
    }
}
