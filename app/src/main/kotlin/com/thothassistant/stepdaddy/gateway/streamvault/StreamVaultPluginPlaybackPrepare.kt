package com.thothassistant.stepdaddy.gateway.streamvault

import android.util.Log
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamResolver
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.executeAsync
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

internal object StreamVaultPluginPlaybackPrepare {
    private const val TAG = "StreamVaultPluginPrepare"

    private val warmupClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Result(
        val handled: Boolean,
        val outputUrl: String? = null,
        val headersJson: String? = null,
        val userAgent: String? = null,
        val message: String? = null,
        val audioJson: String? = null,
    )

    fun prepare(
        inputUrl: String,
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
        supplementSource: SupplementSource?,
    ): Result {
        val normalized = inputUrl.substringBefore('|').trim()
        if (normalized.isBlank()) {
            return Result(handled = false)
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return Result(handled = false)
        val path = uri.path.orEmpty()
        val base = StreamVaultPluginSupport.resolveGatewayBase(prefs, environment)
        if (!isGatewayManagedUrl(uri, base)) {
            return Result(handled = false)
        }

        val startedAtMs = System.currentTimeMillis()
        val warmup = runBlocking {
            runCatching {
                val request = Request.Builder()
                    .url(normalized)
                    .header("User-Agent", GatewayConfig.USER_AGENT)
                    .get()
                    .build()
                warmupClient.executeAsync(request).use { response ->
                    if (!response.isSuccessful) {
                        error("warmup HTTP ${response.code}")
                    }
                    response.body?.close()
                }
            }
        }
        warmup.onFailure { error ->
            Log.w(TAG, "manifest warmup failed target=$normalized reason=${error.message}")
        }.onSuccess {
            Log.i(
                TAG,
                "manifest warmup ok elapsedMs=${System.currentTimeMillis() - startedAtMs} target=$normalized",
            )
        }

        val headers = linkedMapOf<String, String>()
        var userAgent: String? = GatewayConfig.USER_AGENT

        when {
            path.contains("/ntv-stream/") -> {
                val token = path.substringAfterLast('/').removeSuffix(".m3u8")
                val supplement = supplementSource?.ntvChannel(token)
                supplement?.referer?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it }
                supplement?.origin?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Origin"] = it }
                userAgent = GatewayConfig.TIVIMATE_USER_AGENT
            }
            path.contains("/dlhd-event-stream/") ||
                path.contains("/tivimate-stream/dlhd-event-") ||
                path.contains("/dlhd-event-mirror/") -> {
                headers["Referer"] = DlhdEventStreamResolver.EMBED_REFERER
                headers["Origin"] = DlhdEventStreamResolver.EMBED_REFERER.trimEnd('/')
                userAgent = GatewayConfig.TIVIMATE_USER_AGENT
            }
            path.contains("/stream/") || path.contains("/tivimate-stream/") -> {
                val origin = environment?.dlhdBaseUrl?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { com.thothassistant.stepdaddy.gateway.admin.AdminStreamHelper.dlhdOrigin(it) }
                    ?.trimEnd('/')
                    ?: "https://daddylive.org"
                headers["Referer"] = "$origin/"
                headers["Origin"] = origin
                userAgent = GatewayConfig.TIVIMATE_USER_AGENT
            }
            path.contains("/dlhd-event-guide/") -> {
                userAgent = GatewayConfig.TIVIMATE_USER_AGENT
            }
            else -> return Result(handled = false)
        }

        val headersJson = if (headers.isEmpty()) {
            null
        } else {
            buildString {
                append('{')
                headers.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append('"').append(key).append("\":\"").append(value.replace("\"", "\\\"")).append('"')
                }
                append('}')
            }
        }

        return Result(
            handled = true,
            outputUrl = normalized,
            headersJson = headersJson,
            userAgent = userAgent,
            message = warmup.exceptionOrNull()?.message,
            audioJson = StreamVaultPluginSupport.audioJson(environment),
        )
    }

    private fun isGatewayManagedUrl(uri: URI, gatewayBase: String): Boolean {
        val gateway = runCatching { URI(gatewayBase) }.getOrNull() ?: return false
        val hostMatches = uri.host.equals(gateway.host, ignoreCase = true) ||
            uri.host.equals("127.0.0.1", ignoreCase = true) ||
            uri.host.equals("localhost", ignoreCase = true)
        if (!hostMatches) return false
        val gatewayPort = gateway.port.takeIf { it > 0 } ?: 3000
        val uriPort = uri.port.takeIf { it > 0 } ?: when (uri.scheme?.lowercase()) {
            "https" -> 443
            else -> 80
        }
        return uriPort == gatewayPort
    }
}
