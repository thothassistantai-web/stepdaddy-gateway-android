package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveResolver
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DuloStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val resolver: DuloCxLiveResolver,
) {
    suspend fun stream(call: ApplicationCall, channelUuid: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val id = channelUuid.trim()
            if (id.isEmpty()) error("dulo_channel_id_missing")
            if (environment.supplementDuloCxAccessToken.trim().isEmpty()) {
                error("dulo_auth_required — set Live TV access token in Settings / admin")
            }
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    val manifestUrl = resolver.resolveManifestUrl(id)
                    val manifestText = resolver.fetchManifestText(manifestUrl)
                    M3u8Rewriter.rewrite(
                        m3u8Text = manifestText,
                        m3u8Url = manifestUrl,
                        refererHost = DuloCxLiveConfig.REFERER,
                        useProxy = false,
                        apiUrl = environment.loopbackBase(),
                    )
                }
            }
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(
                playlist.toByteArray(StandardCharsets.UTF_8),
                ContentType("application", "vnd.apple.mpegurl"),
            )
        } catch (_: TimeoutCancellationException) {
            respondError(call, HttpStatusCode.GatewayTimeout, "dulo upstream timeout — retry shortly", "3")
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val msg = exc.message ?: "dulo_upstream_error"
            val auth = msg.contains("auth", ignoreCase = true) || msg.contains("Unauthorized", ignoreCase = true)
            respondError(
                call,
                status = if (auth) HttpStatusCode.Unauthorized else HttpStatusCode.BadGateway,
                message = msg,
            )
        }
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
        private const val STREAM_TIMEOUT_MS = 45_000L
    }
}
