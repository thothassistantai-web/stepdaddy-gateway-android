package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        )
    }

    suspend fun genericStream(call: ApplicationCall, channelId: String) {
        stream(
            call,
            channelId,
            attachmentName = "$channelId.m3u8",
            attachment = true,
            useProxy = false,
        )
    }

    private suspend fun stream(
        call: ApplicationCall,
        channelId: String,
        attachmentName: String,
        attachment: Boolean = false,
        useProxy: Boolean = false,
    ) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val playlist = coroutineScope {
                val resolveJob = async {
                    client.resolveStream(
                        channelId,
                        useProxy = useProxy,
                        apiUrl = environment.loopbackBase(),
                    )
                }
                try {
                    withTimeout(GatewayConfig.STREAM_FETCH_TIMEOUT_MS) {
                        resolveJob.await()
                    }
                } catch (exc: TimeoutCancellationException) {
                    resolveJob.cancel()
                    throw exc
                }
            }
            if (attachment) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=$attachmentName",
                )
            }
            val bytes = playlist.toByteArray(StandardCharsets.UTF_8)
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(
                bytes = bytes,
                contentType = ContentType("application", "vnd.apple.mpegurl"),
            )
        } catch (_: TimeoutCancellationException) {
            call.response.header(HttpHeaders.RetryAfter, "3")
            call.respond(
                HttpStatusCode.GatewayTimeout,
                mapOf("error" to "upstream_timeout", "transient" to true),
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (_: IndexOutOfBoundsException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stream not found"))
        } catch (exc: Exception) {
            val transient = isTransientStreamError(exc)
            val status = when {
                exc.message == "upstream_busy" -> HttpStatusCode.ServiceUnavailable
                transient -> HttpStatusCode.GatewayTimeout
                else -> HttpStatusCode.BadGateway
            }
            if (transient || exc.message == "upstream_busy") {
                call.response.header(HttpHeaders.RetryAfter, "3")
            }
            call.respond(
                status,
                mapOf(
                    "error" to (exc.message ?: "upstream_error"),
                    "transient" to (transient || exc.message == "upstream_busy"),
                ),
            )
        }
    }

    private fun isTransientStreamError(exc: Exception): Boolean {
        if (exc is TimeoutCancellationException) {
            return true
        }
        if (exc.message == "upstream_busy") {
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
