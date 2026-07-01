package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.SupplementConfig
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.XyzStreamsConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

class XyzStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    suspend fun tivimateStream(call: ApplicationCall, streamId: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.xyzChannel(streamId)
                ?: error("xyz_channel_not_found")
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolvePlaylist(supplement)
                }
            }
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(
                bytes = playlist.toByteArray(StandardCharsets.UTF_8),
                contentType = ContentType("application", "vnd.apple.mpegurl"),
            )
        } catch (_: TimeoutCancellationException) {
            respondError(
                call,
                status = HttpStatusCode.GatewayTimeout,
                message = "xyzstreams upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val transient = exc.message?.contains("timeout", ignoreCase = true) == true
            respondError(
                call,
                status = if (transient) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
                message = exc.message ?: "xyz_upstream_error",
                retryAfter = if (transient) "3" else null,
            )
        }
    }

    private fun resolvePlaylist(supplement: SupplementChannel): String {
        val manifestUrl = supplement.streamUrl.trim()
        if (manifestUrl.isEmpty()) error("xyz_manifest_missing")
        val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: XyzStreamsConfig.REFERER
        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .header("Referer", referer)
            .header("Origin", supplement.origin?.trim()?.takeIf { it.isNotEmpty() } ?: XyzStreamsConfig.ORIGIN)
            .get()
            .build()
        val manifestText = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("xyz_upstream_${response.code}")
            response.body?.string()?.trim().orEmpty()
        }
        if (!manifestText.startsWith("#EXTM3U")) error("xyz_invalid_manifest")
        return M3u8Rewriter.rewrite(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = referer,
            useProxy = false,
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

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(STREAM_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
