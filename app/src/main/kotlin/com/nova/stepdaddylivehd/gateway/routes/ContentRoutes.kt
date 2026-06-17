package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.upstream.ContentCrypto
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig
import com.nova.stepdaddylivehd.gateway.upstream.M3u8Rewriter
import com.nova.stepdaddylivehd.gateway.upstream.executeAsync
import com.nova.stepdaddylivehd.gateway.upstream.getText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.nio.charset.StandardCharsets

class ContentRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val httpClient: OkHttpClient,
) {
    suspend fun content(call: ApplicationCall, encryptedPath: String) {
        val upstreamUrl = runCatching { ContentCrypto.decrypt(encryptedPath) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_path"))
                return
            }
        try {
            if (isM3u8Url(upstreamUrl)) {
                serveProxiedPlaylist(call, upstreamUrl)
            } else {
                serveBinary(call, upstreamUrl, referer = upstreamUrl)
            }
        } catch (exc: Exception) {
            client.recordHealingAction("content_fail ${exc.message?.take(60)}")
            if (isRetriableContentError(exc)) {
                client.invalidateFreshStreamCaches()
            }
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (exc.message ?: "content_proxy_error")),
            )
        }
    }

    suspend fun key(call: ApplicationCall, encryptedUrl: String, encryptedHost: String) {
        val upstreamUrl = runCatching { ContentCrypto.decrypt(encryptedUrl) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_key_url"))
                return
            }
        val refererHost = runCatching { ContentCrypto.decrypt(encryptedHost) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_key_host"))
                return
            }
        try {
            val referer = if (refererHost.startsWith("http")) refererHost else "https://$refererHost/"
            val request = Request.Builder()
                .url(upstreamUrl)
                .header("User-Agent", GatewayConfig.USER_AGENT)
                .header("Referer", referer)
                .get()
                .build()
            val bytes = withContext(Dispatchers.IO) {
                httpClient.executeAsync(request).use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} for key")
                    }
                    response.body?.bytes() ?: byteArrayOf()
                }
            }
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        } catch (exc: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (exc.message ?: "key_proxy_error")),
            )
        }
    }

    private suspend fun serveProxiedPlaylist(call: ApplicationCall, upstreamUrl: String) {
        val parsed = URL(upstreamUrl)
        val refererCandidates = listOf(
            upstreamUrl,
            "${parsed.protocol}://${parsed.host}/",
            client.activeBaseUrl.trimEnd('/') + "/",
        )
        var playlistText: String? = null
        var lastError: Exception? = null
        for (referer in refererCandidates) {
            try {
                val request = Request.Builder()
                    .url(upstreamUrl)
                    .header("User-Agent", GatewayConfig.USER_AGENT)
                    .header("Referer", referer)
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                playlistText = withContext(Dispatchers.IO) {
                    httpClient.getText(request)
                }
                break
            } catch (exc: Exception) {
                lastError = exc
            }
        }
        if (playlistText == null) {
            throw lastError ?: IllegalStateException("upstream playlist unavailable")
        }
        val rewritten = M3u8Rewriter.rewrite(
            m3u8Text = playlistText,
            m3u8Url = upstreamUrl,
            refererHost = parsed.host,
            useProxy = true,
            apiUrl = environment.loopbackBase(),
            preferLighterVariant = false,
        )
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(rewritten, ContentType("application", "vnd.apple.mpegurl"))
    }

    private suspend fun serveBinary(call: ApplicationCall, upstreamUrl: String, referer: String) {
        val request = Request.Builder()
            .url(upstreamUrl)
            .header("User-Agent", GatewayConfig.USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val bytes = withContext(Dispatchers.IO) {
            httpClient.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} for segment")
                }
                response.body?.bytes() ?: byteArrayOf()
            }
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    private fun isM3u8Url(url: String): Boolean {
        val path = URL(url).path.lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun isRetriableContentError(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        return message.contains("HTTP 403") ||
            message.contains("HTTP 502") ||
            message.contains("HTTP 504") ||
            message.contains("HTTP 500")
    }
}
