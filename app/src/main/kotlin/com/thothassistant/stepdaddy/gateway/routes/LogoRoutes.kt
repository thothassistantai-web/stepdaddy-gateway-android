package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.UrlSafeBase64
import com.thothassistant.stepdaddy.gateway.upstream.executeAsync
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class LogoRoutes(
    private val cacheDir: File,
    private val fallbackSvg: ByteArray,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    suspend fun logo(call: ApplicationCall, token: String) {
        val upstreamUrl = runCatching { UrlSafeBase64.decode(token) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_logo_token"))
                return
            }
        if (!upstreamUrl.startsWith("http://") && !upstreamUrl.startsWith("https://")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_logo_url"))
            return
        }
        cacheDir.mkdirs()
        // Hash the full URL — many CDNs use the same trailing path (/img, logo.png).
        val cacheFile = File(cacheDir, cacheKey(upstreamUrl))
        val failMarker = File(cacheDir, cacheKey(upstreamUrl) + ".fail")
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            respondCached(call, cacheFile, upstreamUrl)
            return
        }
        // Negative-cache dead CDNs so TiviMate does not re-wait the full upstream timeout
        // on every channel-list image request.
        if (failMarker.exists() &&
            System.currentTimeMillis() - failMarker.lastModified() < FAIL_CACHE_TTL_MS
        ) {
            respondFallback(call)
            return
        }
        try {
            val request = Request.Builder()
                .url(upstreamUrl)
                .header("User-Agent", GatewayConfig.USER_AGENT)
                .get()
                .build()
            val bytes = withContext(Dispatchers.IO) {
                httpClient.executeAsync(request).use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: byteArrayOf()
                }
            }
            if (bytes.isEmpty()) {
                respondFallback(call)
                return
            }
            runCatching { failMarker.delete() }
            cacheFile.writeBytes(bytes)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.respondBytes(bytes, contentTypeFor(upstreamUrl))
        } catch (_: Exception) {
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                respondCached(call, cacheFile, upstreamUrl)
                return
            }
            runCatching { failMarker.writeText("1") }
            // Fail fast with placeholder so TiviMate/Glide never spin on dead CDNs.
            respondFallback(call)
        }
    }

    private suspend fun respondFallback(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(fallbackSvg, ContentType.Image.SVG)
    }

    private suspend fun respondCached(call: ApplicationCall, cacheFile: File, upstreamUrl: String) {
        // Avoid Dispatchers.IO — under supplement/EPG stampede, cache hits were waiting seconds
        // on a saturated pool even for tiny already-on-disk logos.
        val bytes = cacheFile.readBytes()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(bytes, contentTypeFor(upstreamUrl))
    }

    private fun contentTypeFor(upstreamUrl: String): ContentType {
        val path = upstreamUrl.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            ext == "svg" || path.endsWith(".svg") -> ContentType.Image.SVG
            ext == "png" || path.endsWith("/img") -> ContentType.Image.PNG
            ext == "jpg" || ext == "jpeg" -> ContentType.Image.JPEG
            ext == "webp" -> ContentType("image", "webp")
            ext == "gif" -> ContentType.Image.GIF
            else -> ContentType.defaultForFilePath(path.substringAfterLast('/').ifBlank { "logo.jpg" })
        }
    }

    companion object {
        private const val FAIL_CACHE_TTL_MS = 6 * 3600_000L

        /** Keep logo proxy snappy — Metahub/CDN stalls must not block TiviMate channel list. */
        private fun defaultClient(): OkHttpClient {
            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 2
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        fun cacheKey(upstreamUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(upstreamUrl.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(40) + ".bin"
        }
    }
}
