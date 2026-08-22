package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveResolver
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.HlsSmartVariantFlattener
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
        try {
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
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            respondError(call, HttpStatusCode.BadGateway, exc.message ?: "supplement_master_error")
        }
    }

    suspend fun supplementMirror(call: ApplicationCall, supplementId: String, mirrorIndex: Int) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.channelById(supplementId)
                ?: return respondError(call, HttpStatusCode.NotFound, "supplement_not_found")
            val fallbacks = supplement.fallbackMirrors
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    if (mirrorIndex == 0) {
                        resolvePrimaryPlaylist(supplement)
                    } else {
                        val fallback = fallbacks.getOrNull(mirrorIndex - 1)
                            ?: error("mirror_index_out_of_range")
                        resolveFallbackPlaylist(fallback)
                    }
                }
            }
            respondPlaylist(call, flattenForSmartMirror(playlist))
        } catch (_: TimeoutCancellationException) {
            respondMirrorError(
                call,
                HttpStatusCode.GatewayTimeout,
                "fallback upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val msg = exc.message ?: "fallback_upstream_error"
            val status = when {
                msg.contains("not_found", ignoreCase = true) ||
                    msg.contains("out_of_range", ignoreCase = true) -> HttpStatusCode.NotFound
                msg.contains("timeout", ignoreCase = true) -> HttpStatusCode.GatewayTimeout
                else -> HttpStatusCode.BadGateway
            }
            respondMirrorError(call, status, msg, retryAfter = if (status == HttpStatusCode.GatewayTimeout) "3" else null)
        }
    }

    suspend fun daddyMaster(call: ApplicationCall, channelId: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
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
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            respondError(call, HttpStatusCode.BadGateway, exc.message ?: "daddy_master_error")
        }
    }

    suspend fun daddyMirror(call: ApplicationCall, channelId: String, mirrorIndex: Int) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val playlist = withContext(Dispatchers.IO) {
                withTimeout(daddyLiveClient.streamFetchTimeoutMs().coerceAtLeast(STREAM_TIMEOUT_MS)) {
                    if (mirrorIndex == 0) {
                        daddyLiveClient.resolveStream(
                            channelId,
                            useProxy = true,
                            apiUrl = environment.loopbackBase(),
                        )
                    } else {
                        val fallback = supplementSource.daddyChannelFallbacks(channelId)
                            .getOrNull(mirrorIndex - 1)
                            ?: error("mirror_index_out_of_range")
                        resolveFallbackPlaylist(fallback)
                    }
                }
            }
            respondPlaylist(call, flattenForSmartMirror(playlist))
        } catch (_: TimeoutCancellationException) {
            respondMirrorError(
                call,
                HttpStatusCode.GatewayTimeout,
                "upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val msg = exc.message ?: "daddy_fallback_error"
            val status = when {
                msg.contains("out_of_range", ignoreCase = true) ||
                    msg.contains("not_found", ignoreCase = true) -> HttpStatusCode.NotFound
                msg.contains("timeout", ignoreCase = true) -> HttpStatusCode.GatewayTimeout
                else -> HttpStatusCode.BadGateway
            }
            respondMirrorError(call, status, msg, retryAfter = if (status == HttpStatusCode.GatewayTimeout) "3" else null)
        }
    }

    private suspend fun resolvePrimaryPlaylist(supplement: SupplementChannel): String {
        val ntvKey = supplement.ntvCdnLiveKey?.trim().orEmpty()
        if (ntvKey.isNotEmpty()) {
            return resolveNtvPlaylist(ntvKey)
        }
        val duloId = supplement.duloChannelId?.trim().orEmpty()
        if (duloId.isNotEmpty()) {
            return resolveDuloPlaylist(duloId)
        }
        return resolveDirectPlaylist(
            url = supplement.streamUrl,
            referer = supplement.referer,
            origin = supplement.origin,
        )
    }

    private suspend fun resolveFallbackPlaylist(mirror: SupplementFallbackMirror): String {
        val ntvKey = mirror.ntvCdnLiveKey?.trim().orEmpty()
        if (ntvKey.isNotEmpty()) {
            return resolveNtvPlaylist(ntvKey)
        }
        val duloId = mirror.duloChannelId?.trim().orEmpty()
        if (duloId.isNotEmpty()) {
            return resolveDuloPlaylist(duloId)
        }
        return resolveDirectPlaylist(
            url = mirror.streamUrl,
            referer = mirror.referer,
            origin = mirror.origin,
        )
    }

    private suspend fun resolveNtvPlaylist(key: String): String {
        val refererHost = ntvResolver.refererForKey(key)
        val manifestUrl = ntvResolver.resolveManifestUrl(key)
        val manifestText = ntvResolver.fetchManifestText(manifestUrl, refererHost)
        return rewriteManifest(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = refererHost,
        )
    }

    private suspend fun resolveDuloPlaylist(channelId: String): String {
        val manifestUrl = duloResolver.resolveManifestUrl(channelId)
        val manifestText = duloResolver.fetchManifestText(manifestUrl)
        return rewriteManifest(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = DuloCxLiveConfig.REFERER,
        )
    }

    private fun resolveDirectPlaylist(url: String, referer: String?, origin: String?): String {
        val streamUrl = url.trim()
        if (streamUrl.isEmpty()) error("fallback_stream_missing")
        val refererHeader = referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: origin?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
        val body = fetchDirectManifest(streamUrl, referer, origin)
        if (isBareHttpUrl(body)) {
            // Upstream returned a redirect-style URL body; fetch that playlist.
            val nestedUrl = body.trim()
            val nested = fetchDirectManifest(nestedUrl, referer, origin)
            return rewriteManifest(nested, nestedUrl, refererHeader)
        }
        return rewriteManifest(body, streamUrl, refererHeader)
    }

    /** Smart backup sub-playlists must be media playlists, not nested masters. */
    private fun flattenForSmartMirror(playlist: String): String {
        val normalized = HlsSmartVariantFlattener.normalizeVersionTag(playlist)
        if (!HlsSmartVariantFlattener.isMasterPlaylist(normalized)) return normalized
        val flattened = HlsSmartVariantFlattener.flattenMasterToMedia(normalized, httpClient)
        if (HlsSmartVariantFlattener.isMasterPlaylist(flattened)) {
            error("smart_mirror_unflattened")
        }
        return flattened
    }

    private fun rewriteManifest(m3u8Text: String, m3u8Url: String, refererHost: String): String {
        val text = m3u8Text.trim()
        if (text.isEmpty()) error("empty_manifest")
        if (isBareHttpUrl(text)) {
            error("manifest_was_url_not_playlist")
        }
        if (!text.contains("#EXT", ignoreCase = true) && !text.startsWith("#EXTM3U", ignoreCase = true)) {
            error("invalid_manifest")
        }
        // Proxy segments through /vod-content|/content so ExoPlayer never fetches CDN
        // URLs without the required Referer (403/Source error on many backups).
        val segmentReferer = refererHost.trim().takeIf { it.isNotEmpty() }
        return HlsSmartVariantFlattener.normalizeVersionTag(
            M3u8Rewriter.rewrite(
                m3u8Text = text,
                m3u8Url = m3u8Url,
                refererHost = refererHost,
                useProxy = true,
                apiUrl = environment.loopbackBase(),
                segmentReferer = segmentReferer,
            ),
        )
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
        // Final safety: never hand ExoPlayer a bare URL or HTML error page as HLS.
        if (isBareHttpUrl(playlist) || playlist.trimStart().startsWith("<")) {
            respondError(call, HttpStatusCode.BadGateway, "invalid_playlist_body")
            return
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(
            playlist.toByteArray(StandardCharsets.UTF_8),
            ContentType("application", "vnd.apple.mpegurl"),
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

    /**
     * Mirror sub-playlists inside a Smart multi-variant master: return HTTP errors without a
     * comment-only m3u8 body so TiviMate can fail over to the next variant instead of ParserException.
     */
    private suspend fun respondMirrorError(
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
        call.respondText(message.take(120), ContentType.Text.Plain, status)
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 55_000L

        fun encodeId(id: String): String = java.net.URLEncoder.encode(id, Charsets.UTF_8.name())

        fun decodeId(encoded: String): String = java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name())

        /** True when [text] is a single http(s) URL with no HLS tags (unsafe to serve as m3u8). */
        fun isBareHttpUrl(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.contains('\r')) return false
            if (trimmed.contains("#EXT", ignoreCase = true)) return false
            return trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        }
    }
}
