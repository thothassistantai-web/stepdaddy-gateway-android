package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodConfig
import com.thothassistant.stepdaddy.gateway.upstream.VidsrcMovieResolver
import com.thothassistant.stepdaddy.gateway.upstream.VodStreamCache
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

class VodStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val resolver: VidsrcMovieResolver,
    private val streamCache: VodStreamCache,
    private val httpClient: OkHttpClient,
) {
    suspend fun movieStream(call: ApplicationCall, tmdbId: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        if (!environment.supplementTmdbMoviesEnabled) {
            respondError(call, HttpStatusCode.NotFound, "tmdb_movies_disabled")
            return
        }
        val normalizedId = tmdbId.trim()
        if (normalizedId.isEmpty() || !normalizedId.all { it.isDigit() }) {
            respondError(call, HttpStatusCode.BadRequest, "invalid_tmdb_id")
            return
        }
        val supplementId = TmdbVodConfig.supplementId(normalizedId.toInt())
        if (supplementSource.vodMovieOrCached(normalizedId) == null &&
            supplementSource.channels().none { it.id == supplementId }
        ) {
            respondError(call, HttpStatusCode.NotFound, "movie_not_in_catalog")
            return
        }
        try {
            val resolved = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolveStream(normalizedId)
                }
            }
            if (resolved.isHls) {
                val manifest = withContext(Dispatchers.IO) {
                    fetchManifestText(resolved.url, resolved.referer)
                }
                val rewritten = M3u8Rewriter.rewrite(
                    m3u8Text = manifest,
                    m3u8Url = resolved.url,
                    refererHost = resolved.referer,
                    useProxy = false,
                    apiUrl = environment.loopbackBase(),
                )
                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondBytes(
                    bytes = rewritten.toByteArray(StandardCharsets.UTF_8),
                    contentType = ContentType("application", "vnd.apple.mpegurl"),
                )
            } else {
                call.respondRedirect(resolved.url, permanent = false)
            }
        } catch (_: TimeoutCancellationException) {
            respondError(call, HttpStatusCode.GatewayTimeout, "vod_upstream_timeout", retryAfter = "5")
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val transient = exc.message?.contains("timeout", ignoreCase = true) == true
            respondError(
                call,
                if (transient) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
                exc.message ?: "vod_resolve_failed",
                retryAfter = if (transient) "5" else null,
            )
        }
    }

    private fun resolveStream(tmdbId: String): VidsrcMovieResolver.ResolvedStream {
        streamCache.get(tmdbId)?.let { return it }
        val resolved = resolver.resolveMovie(tmdbId)
        streamCache.put(tmdbId, resolved)
        return resolved
    }

    private fun fetchManifestText(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
            .header("Referer", referer)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("manifest HTTP ${response.code}")
            return response.body?.string().orEmpty()
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
        private const val STREAM_TIMEOUT_MS = 55_000L
    }
}
