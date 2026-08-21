package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveResolver
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.MirrorHlsManifest
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveResolver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

class SupplementFallbackStreamRoutes(
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val daddyLiveClient: DaddyLiveClient,
    private val ntvResolver: NtvCxCdnLiveResolver,
    private val duloResolver: DuloCxLiveResolver,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun supplementMaster(call: ApplicationCall, supplementId: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val supplement = supplementSource.channelById(supplementId)
            ?: return respondError(call, HttpStatusCode.NotFound, "supplement_not_found")
        val fallbacks = supplement.fallbackMirrors
        if (fallbacks.isEmpty()) {
            return respondError(call, HttpStatusCode.NotFound, "no_fallbacks")
        }
        val base = environment.loopbackBase()
        val encoded = encodeId(supplementId)
        val labels = listOf("Primary") + fallbacks.map { it.label.ifBlank { "Fallback" } }
        val manifest = MirrorHlsManifest.build(
            baseUrl = base,
            eventToken = "supplement-$encoded",
            mirrorCount = 1 + fallbacks.size,
            labels = labels,
            pathPrefix = "supplement-stream/$encoded",
        )
        respondPlaylist(call, manifest)
    }

    suspend fun supplementMirror(call: ApplicationCall, supplementId: String, mirrorIndex: Int) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val supplement = supplementSource.channelById(supplementId)
            ?: return respondError(call, HttpStatusCode.NotFound, "supplement_not_found")
        val fallbacks = supplement.fallbackMirrors
        if (mirrorIndex == 0) {
            val playlist = resolvePrimaryPlaylist(supplement)
            respondPlaylist(call, playlist)
            return
        }
        val fallback = fallbacks.getOrNull(mirrorIndex - 1)
            ?: return respondError(call, HttpStatusCode.NotFound, "mirror_index_out_of_range")
        val playlist = resolveFallbackPlaylist(fallback)
        respondPlaylist(call, playlist)
    }

    suspend fun daddyMaster(call: ApplicationCall, channelId: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val fallbacks = supplementSource.daddyChannelFallbacks(channelId)
        if (fallbacks.isEmpty()) {
            return respondError(call, HttpStatusCode.NotFound, "no_fallbacks")
        }
        val base = environment.loopbackBase()
        val labels = listOf("DaddyLive") + fallbacks.map { it.label.ifBlank { "Supplement" } }
        val manifest = MirrorHlsManifest.build(
            baseUrl = base,
            eventToken = "daddy-$channelId",
            mirrorCount = 1 + fallbacks.size,
            labels = labels,
            pathPrefix = "daddy-fallback/$channelId",
        )
        respondPlaylist(call, manifest)
    }

    suspend fun daddyMirror(call: ApplicationCall, channelId: String, mirrorIndex: Int) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        if (mirrorIndex == 0) {
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(daddyLiveClient.streamFetchTimeoutMs()) {
                    daddyLiveClient.resolveStream(
                        channelId,
                        useProxy = true,
                        apiUrl = environment.loopbackBase(),
                    )
                }
            }
            respondPlaylist(call, playlist)
            return
        }
        val fallback = supplementSource.daddyChannelFallbacks(channelId).getOrNull(mirrorIndex - 1)
            ?: return respondError(call, HttpStatusCode.NotFound, "mirror_index_out_of_range")
        val playlist = resolveFallbackPlaylist(fallback)
        respondPlaylist(call, playlist)
    }

    private suspend fun resolvePrimaryPlaylist(supplement: SupplementChannel): String =
        withContext(Dispatchers.IO) {
            if (!supplement.ntvCdnLiveKey.isNullOrBlank()) {
                return@withContext ntvResolver.resolveManifestUrl(supplement.ntvCdnLiveKey!!)
            }
            val duloId = supplement.duloChannelId?.trim().orEmpty()
            if (duloId.isNotEmpty()) {
                return@withContext duloResolver.resolveManifestUrl(duloId)
            }
            fetchDirectManifest(supplement.streamUrl, supplement.referer, supplement.origin)
        }

    private suspend fun resolveFallbackPlaylist(mirror: SupplementFallbackMirror): String =
        withContext(Dispatchers.IO) {
            val ntvKey = mirror.ntvCdnLiveKey?.trim().orEmpty()
            if (ntvKey.isNotEmpty()) {
                return@withContext ntvResolver.resolveManifestUrl(ntvKey)
            }
            val duloId = mirror.duloChannelId?.trim().orEmpty()
            if (duloId.isNotEmpty()) {
                return@withContext duloResolver.resolveManifestUrl(duloId)
            }
            val url = mirror.streamUrl.trim()
            if (url.isEmpty()) error("fallback_stream_missing")
            fetchDirectManifest(url, mirror.referer, mirror.origin)
        }

    private fun fetchDirectManifest(url: String, referer: String?, origin: String?): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NtvCxCdnLiveConfig.CATALOG_USER_AGENT)
            .apply {
                referer?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Referer", it) }
                origin?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Origin", it) }
            }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("upstream_${response.code}")
            return response.body?.string()?.trim().orEmpty().ifEmpty { error("empty_manifest") }
        }
    }

    private suspend fun respondPlaylist(call: ApplicationCall, playlist: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(
            playlist.toByteArray(StandardCharsets.UTF_8),
            ContentType("application", "vnd.apple.mpegurl"),
        )
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondText(
            HlsErrorManifest.build(message),
            ContentType("application", "vnd.apple.mpegurl"),
            status,
        )
    }

    companion object {
        fun encodeId(id: String): String = java.net.URLEncoder.encode(id, Charsets.UTF_8.name())

        fun decodeId(encoded: String): String = java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name())
    }
}
