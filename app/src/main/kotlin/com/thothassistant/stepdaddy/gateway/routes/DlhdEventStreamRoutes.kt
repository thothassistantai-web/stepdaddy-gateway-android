package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamResolver
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
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

class DlhdEventStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val resolver: DlhdEventStreamResolver = DlhdEventStreamResolver(),
) {
    suspend fun eventStream(call: ApplicationCall, token: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.dlhdEventChannel(token)
                ?: error("dlhd_event_not_found")
            val key = supplement.dlhdEventStreamKey?.trim().orEmpty()
            if (key.isEmpty()) error("dlhd_event_key_missing")
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolvePlaylist(supplement, key)
                }
            }
            val bytes = playlist.toByteArray(StandardCharsets.UTF_8)
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(bytes, ContentType("application", "vnd.apple.mpegurl"))
        } catch (_: TimeoutCancellationException) {
            respondError(call, HttpStatusCode.GatewayTimeout, "dlhd event upstream timeout")
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            respondError(
                call,
                if (exc.message?.contains("timeout", ignoreCase = true) == true) {
                    HttpStatusCode.GatewayTimeout
                } else {
                    HttpStatusCode.BadGateway
                },
                exc.message ?: "dlhd_event_upstream_error",
            )
        }
    }

    suspend fun guideStream(call: ApplicationCall) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val body = HlsErrorManifest.build("Special Events schedule — select a stream below")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(body.toByteArray(StandardCharsets.UTF_8), ContentType("application", "vnd.apple.mpegurl"))
    }

    private fun resolvePlaylist(supplement: SupplementChannel, key: String): String {
        if (key.startsWith("tv|", ignoreCase = true)) {
            error("numeric_tv_streams_use_tivimate_route")
        }
        val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: DlhdEventStreamResolver.EMBED_REFERER
        val manifestUrl = resolver.resolveManifestUrl(key, referer)
            ?: error("dlhd_event_manifest_unresolved")
        val manifestText = resolver.fetchManifestText(manifestUrl, referer)
            ?: error("dlhd_event_manifest_fetch_failed")
        val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() } ?: referer.trimEnd('/')
        return M3u8Rewriter.rewrite(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = origin,
            useProxy = false,
            apiUrl = environment.loopbackBase(),
            preferLighterVariant = true,
        )
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
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
