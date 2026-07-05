package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodConfig
import com.thothassistant.stepdaddy.gateway.upstream.VidsrcMovieResolver
import com.thothassistant.stepdaddy.gateway.upstream.VodMovieResolver
import com.thothassistant.stepdaddy.gateway.upstream.VodStreamCache
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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
    private val resolver: VodMovieResolver,
    private val streamCache: VodStreamCache,
    private val httpClient: OkHttpClient,
) {
    suspend fun seriesStream(
        call: ApplicationCall,
        showTmdbId: String,
        season: String,
        episode: String,
    ) {
        if (redirectMp4ToM3u8IfNeeded(call)) return
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        if (!environment.supplementTmdbMoviesEnabled) {
            respondError(call, HttpStatusCode.NotFound, "vod_disabled")
            return
        }
        val tmdbId = showTmdbId.trim()
        val seasonNum = season.trim().toIntOrNull()
        val episodeNum = episode.trim().toIntOrNull()
        if (tmdbId.isEmpty() || !tmdbId.all { it.isDigit() } || seasonNum == null || episodeNum == null) {
            respondError(call, HttpStatusCode.BadRequest, "invalid_series_params")
            return
        }
        if (seasonNum <= 0 || episodeNum <= 0) {
            respondError(call, HttpStatusCode.BadRequest, "invalid_series_params")
            return
        }
        val supplementId = TmdbVodConfig.seriesSupplementId(tmdbId.toInt(), seasonNum, episodeNum)
        val catalogRow = supplementSource.vodEpisodeOrCached(tmdbId, seasonNum, episodeNum)
        if (catalogRow == null &&
            supplementSource.channels().none { it.id == supplementId }
        ) {
            respondError(call, HttpStatusCode.NotFound, "episode_not_in_catalog")
            return
        }
        val imdbId = catalogRow?.imdbId
        val showTitle = catalogRow?.let { showTitleFromEpisodeName(it.name) }
        try {
            val resolved = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolveEpisodeStream(tmdbId, seasonNum, episodeNum, imdbId, showTitle)
                }
            }
            deliverStream(call, resolved)
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

    suspend fun movieStream(call: ApplicationCall, tmdbId: String) {
        if (redirectMp4ToM3u8IfNeeded(call)) return
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
        val catalogRow = supplementSource.vodMovieOrCached(normalizedId)
        if (catalogRow == null &&
            supplementSource.channels().none { it.id == supplementId }
        ) {
            respondError(call, HttpStatusCode.NotFound, "movie_not_in_catalog")
            return
        }
        val imdbId = catalogRow?.imdbId
        val title = catalogRow?.name?.let { name ->
            name.substringBefore(" (").trim().ifBlank {
                TmdbVodConfig.parseListTitle(name).title
            }
        }
        try {
            val resolved = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolveStream(normalizedId, imdbId, title)
                }
            }
            deliverStream(call, resolved)
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

    private fun resolveStream(
        tmdbId: String,
        imdbId: String?,
        title: String?,
    ): VidsrcMovieResolver.ResolvedStream {
        val cacheKey = buildString {
            append(tmdbId)
            imdbId?.let { append('|').append(it) }
        }
        streamCache.get(cacheKey)?.let { return it }
        val resolved = resolver.resolveMovie(tmdbId, imdbId, title)
        streamCache.put(cacheKey, resolved)
        return resolved
    }

    private fun resolveEpisodeStream(
        showTmdbId: String,
        season: Int,
        episode: Int,
        imdbId: String?,
        showTitle: String?,
    ): VidsrcMovieResolver.ResolvedStream {
        val cacheKey = "series:$showTmdbId:$season:$episode:${imdbId.orEmpty()}"
        streamCache.get(cacheKey)?.let { return it }
        val resolved = resolver.resolveEpisode(showTmdbId, season, episode, imdbId, showTitle)
        streamCache.put(cacheKey, resolved)
        return resolved
    }

    private suspend fun redirectMp4ToM3u8IfNeeded(call: ApplicationCall): Boolean {
        val path = call.request.path()
        if (!path.endsWith(".mp4", ignoreCase = true)) return false
        val m3u8Path = path.replace(Regex("""\.mp4$""", RegexOption.IGNORE_CASE), ".m3u8")
        call.respondRedirect(m3u8Path, permanent = false)
        return true
    }

    private suspend fun deliverStream(
        call: ApplicationCall,
        resolved: VidsrcMovieResolver.ResolvedStream,
    ) {
        val requestPath = call.request.path()
        if (resolved.isHls) {
            if (requestPath.endsWith(".mp4", ignoreCase = true)) {
                // TiviMate M3U uses .mp4 for VOD classification; actual media is HLS.
                val m3u8Path = requestPath.replace(Regex("""\.mp4$""", RegexOption.IGNORE_CASE), ".m3u8")
                call.respondRedirect(m3u8Path, permanent = false)
                return
            }
            val manifest = withContext(Dispatchers.IO) {
                fetchManifestText(resolved.url, resolved.referer)
            }
            val rewritten = M3u8Rewriter.rewrite(
                m3u8Text = manifest,
                m3u8Url = resolved.url,
                refererHost = resolved.referer,
                useProxy = true,
                apiUrl = environment.loopbackBase(),
                segmentReferer = resolved.referer,
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
    }

    private fun showTitleFromEpisodeName(name: String): String? {
        val match = Regex("""^(.+?) S\d{2}E\d{2}$""").find(name.trim()) ?: return null
        return match.groupValues[1].trim().ifBlank { null }
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
