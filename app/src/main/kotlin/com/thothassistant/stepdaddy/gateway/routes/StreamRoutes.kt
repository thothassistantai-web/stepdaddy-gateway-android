package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets

class StreamRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
) {
    suspend fun tivimateStream(call: ApplicationCall, channelId: String) {
        stream(
            call,
            channelId,
            attachmentName = "$channelId.m3u8",
            useProxy = true,
            hlsErrors = true,
        )
    }

    suspend fun genericStream(call: ApplicationCall, channelId: String) {
        stream(
            call,
            channelId,
            attachmentName = "$channelId.m3u8",
            attachment = true,
            useProxy = false,
            hlsErrors = false,
        )
    }

    private suspend fun stream(
        call: ApplicationCall,
        channelId: String,
        attachmentName: String,
        attachment: Boolean = false,
        useProxy: Boolean = false,
        hlsErrors: Boolean = false,
    ) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val id = channelId.trim()
        if (id.isEmpty()) {
            respondStreamError(
                call,
                hlsErrors = hlsErrors,
                status = HttpStatusCode.NotFound,
                message = "channel not found",
            )
            return
        }
        try {
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(client.streamFetchTimeoutMs()) {
                    client.resolveStream(
                        id,
                        useProxy = useProxy,
                        apiUrl = environment.loopbackBase(),
                    )
                }
            }
            val servedStale = client.wasLastServeFromStaleCache()
            client.noteStreamSuccess(id)
            if (attachment) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=$attachmentName",
                )
            }
            val bytes = playlist.toByteArray(StandardCharsets.UTF_8)
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            if (servedStale) {
                call.response.header("X-StepDaddy-Cache", "stale-good")
            }
            call.respondBytes(
                bytes = bytes,
                contentType = ContentType("application", "vnd.apple.mpegurl"),
            )
        } catch (_: TimeoutCancellationException) {
            client.noteStreamFailure(id, IllegalStateException("upstream_timeout"))
            respondStreamError(
                call,
                hlsErrors = hlsErrors,
                status = HttpStatusCode.GatewayTimeout,
                message = "upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (_: IndexOutOfBoundsException) {
            client.noteStreamFailure(id, IllegalStateException("stream_not_found"))
            respondStreamError(
                call,
                hlsErrors = hlsErrors,
                status = HttpStatusCode.NotFound,
                message = "channel not found",
            )
        } catch (exc: Exception) {
            client.noteStreamFailure(id, exc)
            val transient = isTransientStreamError(exc)
            val status = when {
                exc.message == "upstream_busy" -> HttpStatusCode.ServiceUnavailable
                exc.message == "upstream_outage" -> HttpStatusCode.ServiceUnavailable
                transient -> HttpStatusCode.GatewayTimeout
                else -> HttpStatusCode.BadGateway
            }
            respondStreamError(
                call,
                hlsErrors = hlsErrors,
                status = status,
                message = if (exc.message == "upstream_outage") {
                    "upstream connectivity degraded; serving cached entries when available"
                } else {
                    exc.message ?: "upstream_error"
                },
                retryAfter = if (transient || exc.message == "upstream_busy" || exc.message == "upstream_outage") "3" else null,
            )
        }
    }

    private suspend fun respondStreamError(
        call: ApplicationCall,
        hlsErrors: Boolean,
        status: HttpStatusCode,
        message: String,
        retryAfter: String? = null,
    ) {
        if (retryAfter != null) {
            call.response.header(HttpHeaders.RetryAfter, retryAfter)
        }
        if (hlsErrors) {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(
                HlsErrorManifest.build(message),
                ContentType("application", "vnd.apple.mpegurl"),
                status,
            )
            return
        }
        call.respond(
            status,
            mapOf("error" to message, "transient" to (retryAfter != null)),
        )
    }

    private fun isTransientStreamError(exc: Exception): Boolean {
        if (exc is TimeoutCancellationException) {
            return true
        }
        if (exc.message == "upstream_busy") {
            return true
        }
        if (exc.message == "upstream_outage") {
            return true
        }
        val message = exc.message.orEmpty()
        if (message.contains("timeout", ignoreCase = true)) {
            return true
        }
        if (message.contains("timed out", ignoreCase = true)) {
            return true
        }
        return false
    }
}
