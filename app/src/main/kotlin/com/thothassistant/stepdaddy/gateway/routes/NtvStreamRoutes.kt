package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveResolver
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class NtvStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val resolver: NtvCxCdnLiveResolver,
) {
    suspend fun tivimateStream(call: ApplicationCall, token: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.ntvChannel(token)
                ?: error("ntv_channel_not_found")
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolvePlaylist(supplement)
                }
            }
            val bytes = playlist.toByteArray(StandardCharsets.UTF_8)
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(
                bytes = bytes,
                contentType = ContentType("application", "vnd.apple.mpegurl"),
            )
        } catch (_: TimeoutCancellationException) {
            respondError(
                call,
                status = HttpStatusCode.GatewayTimeout,
                message = "ntv upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val transient = exc.message?.contains("timeout", ignoreCase = true) == true
            respondError(
                call,
                status = if (transient) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
                message = exc.message ?: "ntv_upstream_error",
                retryAfter = if (transient) "3" else null,
            )
        }
    }

    private suspend fun resolvePlaylist(supplement: SupplementChannel): String {
        val key = supplement.ntvCdnLiveKey?.trim().orEmpty()
        if (key.isEmpty()) error("ntv_key_missing")
        val parts = key.split("|", limit = 2)
        if (parts.size != 2) error("ntv_key_invalid")
        val manifestUrl = resolver.resolveManifestUrl(parts[0], parts[1])
        val manifestText = resolver.fetchManifestText(manifestUrl)
        return M3u8Rewriter.rewrite(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = NtvCxCdnLiveConfig.REFERER,
            useProxy = true,
            apiUrl = environment.loopbackBase(),
        )
    }

    private suspend fun respondError(
        call: ApplicationCall,
        status: HttpStatusCode,
        message: String,
        retryAfter: String? = null,
    ) {
        if (retryAfter != null) {
            call.response.header(HttpHeaders.RetryAfter, retryAfter)
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(
            HlsErrorManifest.build(message),
            ContentType("application", "vnd.apple.mpegurl"),
            status,
        )
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 55_000L
    }
}
