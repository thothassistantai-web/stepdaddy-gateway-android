package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.ContentCrypto
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveErrorClassifier
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.HttpStatusException
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.executeAsync
import com.thothassistant.stepdaddy.gateway.upstream.getText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.min

class ContentRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val httpClient: OkHttpClient,
) {
    private val dlhdHostFailureCounts = mutableMapOf<String, Int>()
    private val dlhdHostCooldownUntilMs = mutableMapOf<String, Long>()
    private val dlhdHostLock = Any()

    suspend fun content(call: ApplicationCall, encryptedPath: String) {
        val upstreamUrl = runCatching { ContentCrypto.decrypt(encryptedPath) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_path"))
                return
            }
        try {
            if (isM3u8Url(upstreamUrl)) {
                serveProxiedPlaylist(call, upstreamUrl)
            } else {
                serveBinary(call, upstreamUrl, referer = upstreamUrl)
            }
        } catch (exc: Exception) {
            client.recordHealingAction("content_fail ${exc.message?.take(60)}")
            if (isRetriableContentError(exc)) {
                client.invalidateFreshStreamCaches()
            }
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (exc.message ?: "content_proxy_error")),
            )
        }
    }

    suspend fun vodContent(call: ApplicationCall, encryptedUrl: String, encryptedReferer: String) {
        val upstreamUrl = runCatching { ContentCrypto.decrypt(encryptedUrl) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_vod_url"))
                return
            }
        val embedReferer = runCatching { ContentCrypto.decrypt(encryptedReferer) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_vod_referer"))
                return
            }
        try {
            if (isM3u8Url(upstreamUrl)) {
                serveProxiedVodPlaylist(call, upstreamUrl, embedReferer)
            } else {
                serveBinary(call, upstreamUrl, referer = embedReferer)
            }
        } catch (exc: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (exc.message ?: "vod_content_proxy_error")),
            )
        }
    }

    suspend fun key(call: ApplicationCall, encryptedUrl: String, encryptedHost: String) {
        val upstreamUrl = runCatching { ContentCrypto.decrypt(encryptedUrl) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_key_url"))
                return
            }
        val refererHost = runCatching { ContentCrypto.decrypt(encryptedHost) }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_key_host"))
                return
            }
        try {
            val referer = if (refererHost.startsWith("http")) refererHost else "https://$refererHost/"
            val request = Request.Builder()
                .url(upstreamUrl)
                .header("User-Agent", GatewayConfig.USER_AGENT)
                .header("Referer", referer)
                .get()
                .build()
            val bytes = withContext(Dispatchers.IO) {
                httpClient.executeAsync(request).use { response ->
                    if (!response.isSuccessful) {
                        throw HttpStatusException(
                            code = response.code,
                            url = response.request.url,
                            responseMessage = response.message,
                        )
                    }
                    response.body?.bytes() ?: byteArrayOf()
                }
            }
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        } catch (exc: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (exc.message ?: "key_proxy_error")),
            )
        }
    }

    private suspend fun serveProxiedPlaylist(call: ApplicationCall, upstreamUrl: String) {
        val parsed = URL(upstreamUrl)
        val candidates = buildPlaylistCandidates(upstreamUrl, parsed)
        var playlistText: String? = null
        var lastError: Exception? = null
        var resolvedUrl: String? = null
        var resolvedHost: String? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate.url)
                    .header("User-Agent", GatewayConfig.USER_AGENT)
                    .header("Referer", candidate.referer)
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                playlistText = withContext(Dispatchers.IO) {
                    httpClient.getText(request)
                }
                resolvedUrl = candidate.url
                resolvedHost = runCatching { URL(candidate.url).host }.getOrNull()
                candidate.cooldownKey?.let { markDlhdHostSuccess(it) }
                break
            } catch (exc: Exception) {
                lastError = exc
                candidate.cooldownKey?.let { markDlhdHostFailure(it) }
            }
        }
        if (playlistText == null) {
            throw lastError ?: IllegalStateException("upstream playlist unavailable")
        }
        val rewriteUrl = resolvedUrl ?: upstreamUrl
        val segmentReferer = resolvedHost?.takeIf { isDlhdHost(it) }?.let { "https://${it.trimEnd('/')}/" }
        val rewritten = M3u8Rewriter.rewrite(
            m3u8Text = playlistText,
            m3u8Url = rewriteUrl,
            refererHost = resolvedHost ?: parsed.host,
            useProxy = true,
            apiUrl = environment.loopbackBase(),
            preferLighterVariant = false,
            segmentReferer = segmentReferer,
        )
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(rewritten, ContentType("application", "vnd.apple.mpegurl"))
    }

    private suspend fun serveProxiedVodPlaylist(
        call: ApplicationCall,
        upstreamUrl: String,
        embedReferer: String,
    ) {
        val request = Request.Builder()
            .url(upstreamUrl)
            .header("User-Agent", GatewayConfig.USER_AGENT)
            .header("Referer", embedReferer)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val playlistText = withContext(Dispatchers.IO) {
            httpClient.getText(request)
        }
        val rewritten = M3u8Rewriter.rewrite(
            m3u8Text = playlistText,
            m3u8Url = upstreamUrl,
            refererHost = embedReferer,
            useProxy = true,
            apiUrl = environment.loopbackBase(),
            preferLighterVariant = false,
            segmentReferer = embedReferer,
        )
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(rewritten, ContentType("application", "vnd.apple.mpegurl"))
    }

    private suspend fun serveBinary(call: ApplicationCall, upstreamUrl: String, referer: String) {
        val request = Request.Builder()
            .url(upstreamUrl)
            .header("User-Agent", GatewayConfig.USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val bytes = withContext(Dispatchers.IO) {
            httpClient.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    throw HttpStatusException(
                        code = response.code,
                        url = response.request.url,
                        responseMessage = response.message,
                    )
                }
                response.body?.bytes() ?: byteArrayOf()
            }
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    private fun isM3u8Url(url: String): Boolean {
        val path = URL(url).path.lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun buildPlaylistCandidates(upstreamUrl: String, parsed: URL): List<PlaylistFetchCandidate> {
        val candidates = mutableListOf<PlaylistFetchCandidate>()
        if (isDlhdHost(parsed.host)) {
            val path = parsed.file
            for (host in orderedDlhdEmbedHosts(parsed.host)) {
                val candidateUrl = URL(parsed.protocol, host, parsed.port, path).toString()
                candidates += PlaylistFetchCandidate(
                    url = candidateUrl,
                    referer = "https://${host.trimEnd('/')}/",
                    cooldownKey = hostKey(host),
                )
            }
        }
        val fallbackReferers = listOf(
            upstreamUrl,
            "${parsed.protocol}://${parsed.host}/",
            client.activeBaseUrl.trimEnd('/') + "/",
        )
        for (referer in fallbackReferers) {
            candidates += PlaylistFetchCandidate(url = upstreamUrl, referer = referer)
        }
        return candidates.distinctBy { "${it.url}|${it.referer}" }
    }

    private fun orderedDlhdEmbedHosts(primaryHost: String?): List<String> {
        val ordered = linkedSetOf<String>()
        primaryHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { ordered += it }
        hostFromBase(client.activeBaseUrl)?.let { ordered += it }
        GatewayConfig.DLHD_EMBED_HOSTS.mapNotNull(::hostFromBase).forEach { ordered += it }
        val hosts = ordered.toList()
        val eligible = hosts.filterNot { isDlhdHostCoolingDown(it) }
        return eligible.ifEmpty { hosts }
    }

    private fun isDlhdHostCoolingDown(host: String): Boolean {
        val key = hostKey(host)
        if (key.isEmpty()) return false
        val retryAt = synchronized(dlhdHostLock) { dlhdHostCooldownUntilMs[key] } ?: return false
        if (System.currentTimeMillis() >= retryAt) {
            synchronized(dlhdHostLock) {
                dlhdHostCooldownUntilMs.remove(key)
                dlhdHostFailureCounts.remove(key)
            }
            return false
        }
        return true
    }

    private fun markDlhdHostFailure(host: String) {
        val key = hostKey(host)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val nextCount = synchronized(dlhdHostLock) {
            val next = (dlhdHostFailureCounts[key] ?: 0) + 1
            dlhdHostFailureCounts[key] = next
            next
        }
        val backoff = min(
            GatewayConfig.DLHD_HOST_COOLDOWN_BASE_MS * (1L shl min(nextCount - 1, 4)),
            GatewayConfig.DLHD_HOST_COOLDOWN_MAX_MS,
        )
        synchronized(dlhdHostLock) {
            dlhdHostCooldownUntilMs[key] = now + backoff
        }
    }

    private fun markDlhdHostSuccess(host: String) {
        val key = hostKey(host)
        if (key.isEmpty()) return
        synchronized(dlhdHostLock) {
            dlhdHostFailureCounts.remove(key)
            dlhdHostCooldownUntilMs.remove(key)
        }
    }

    private fun isDlhdHost(host: String): Boolean =
        GatewayConfig.DADDYLIVE_HOSTS.any { hostMatches(host.lowercase(), it) }

    private fun hostFromBase(baseUrl: String): String? =
        runCatching { URL(baseUrl).host.lowercase() }.getOrNull()

    private fun hostMatches(host: String, token: String): Boolean =
        host == token || host.endsWith(".$token")

    private fun hostKey(hostOrUrl: String): String =
        runCatching { URL(hostOrUrl).host.lowercase() }.getOrNull()
            ?: hostOrUrl.trim().lowercase()

    private data class PlaylistFetchCandidate(
        val url: String,
        val referer: String,
        val cooldownKey: String? = null,
    )

    private fun isRetriableContentError(exc: Exception): Boolean {
        val status = exc as? HttpStatusException
        if (status != null) {
            if (status.code == 403 && DaddyLiveErrorClassifier.isXameleonUrl(status.url)) {
                return true
            }
            return status.code == 403 || status.code == 502 || status.code == 504 || status.code == 500
        }
        val message = exc.message.orEmpty()
        if (message.contains("HTTP 403") && message.contains("xameleon", ignoreCase = true)) {
            return true
        }
        return message.contains("HTTP 403") ||
            message.contains("HTTP 502") ||
            message.contains("HTTP 504") ||
            message.contains("HTTP 500")
    }
}
