package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig
import com.nova.stepdaddylivehd.gateway.upstream.UrlSafeBase64
import com.nova.stepdaddylivehd.gateway.upstream.executeAsync
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
        val fileName = upstreamUrl.substringAfterLast('/').take(120).ifBlank { "logo.bin" }
        val cacheFile = File(cacheDir, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            respondCached(call, cacheFile)
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
            cacheFile.writeBytes(bytes)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.respondBytes(bytes, contentTypeFor(fileName))
        } catch (exc: Exception) {
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                respondCached(call, cacheFile)
                return
            }
            respondFallback(call)
        }
    }

    private suspend fun respondFallback(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(fallbackSvg, ContentType.Image.SVG)
    }

    private suspend fun respondCached(call: ApplicationCall, cacheFile: File) {
        val bytes = withContext(Dispatchers.IO) { cacheFile.readBytes() }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(bytes, contentTypeFor(cacheFile.name))
    }

    private fun contentTypeFor(fileName: String): ContentType {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "webp" -> ContentType("image", "webp")
            "gif" -> ContentType.Image.GIF
            else -> ContentType.defaultForFilePath(fileName)
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .build()
    }
}
