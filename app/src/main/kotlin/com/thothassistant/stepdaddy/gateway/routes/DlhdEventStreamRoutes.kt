package com.thothassistant.stepdaddy.gateway.routes

import android.content.Context
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamHealth
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamResolver
import com.thothassistant.stepdaddy.gateway.upstream.GuideScheduleHlsManifest
import com.thothassistant.stepdaddy.gateway.upstream.GuideScheduleMediaCache
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventCategoryEmoji
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsGuideBitmapRenderer
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsGuideHtmlRenderer
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DlhdEventStreamRoutes(
    context: Context,
    private val environment: GatewayEnvironment,
    private val supplementSource: SupplementSource,
    private val resolver: DlhdEventStreamResolver = DlhdEventStreamResolver(),
) {
    private val appContext = context.applicationContext
    private val guideMediaCache = GuideScheduleMediaCache(appContext)

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
            supplementSource.recordDlhdEventStreamHealth(
                token,
                DlhdEventStreamHealth.ProbeResult.healthy(),
            )
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(bytes, ContentType("application", "vnd.apple.mpegurl"))
        } catch (_: TimeoutCancellationException) {
            supplementSource.recordDlhdEventStreamHealth(
                token,
                DlhdEventStreamHealth.ProbeResult.unhealthy("upstream_timeout"),
            )
            respondError(
                call,
                HttpStatusCode.GatewayTimeout,
                "dlhd event upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            val transient = exc.message?.contains("timeout", ignoreCase = true) == true
            supplementSource.recordDlhdEventStreamHealth(
                token,
                DlhdEventStreamHealth.ProbeResult.unhealthy(exc.message ?: "upstream_error"),
            )
            respondError(
                call,
                if (transient) {
                    HttpStatusCode.GatewayTimeout
                } else {
                    HttpStatusCode.BadGateway
                },
                exc.message ?: "dlhd_event_upstream_error",
                retryAfter = if (transient) "3" else null,
            )
        }
    }

    suspend fun guidePage(call: ApplicationCall, slug: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType.Text.Html)
            return
        }
        val model = guideModel(slug) ?: run {
            call.respondText(guideNotFoundHtml(slug), ContentType.Text.Html, HttpStatusCode.NotFound)
            return
        }
        val rendered = SpecialEventsGuideHtmlRenderer.render(
            category = model.category,
            emoji = model.emoji,
            events = model.events,
            baseUrl = environment.loopbackBase(),
        )
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondText(rendered.html, ContentType.Text.Html)
    }

    suspend fun guideStream(call: ApplicationCall, slug: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val normalized = normalizeGuideSlug(slug) ?: run {
            respondError(call, HttpStatusCode.NotFound, "guide_not_found")
            return
        }
        val base = environment.loopbackBase().trimEnd('/')
        val mp4Url = "$base/dlhd-event-guide/$normalized.mp4"
        val body = GuideScheduleHlsManifest.build(mp4Url)
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(body.toByteArray(StandardCharsets.UTF_8), ContentType("application", "vnd.apple.mpegurl"))
    }

    suspend fun guideMp4(call: ApplicationCall, slug: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("video", "mp4"))
            return
        }
        val model = guideModel(slug) ?: run {
            respondError(call, HttpStatusCode.NotFound, "guide_not_found")
            return
        }
        val contentKey = supplementSource.guideScheduleContentKey(model.guideId)
        val mp4 = withContext(Dispatchers.IO) {
            guideMediaCache.getOrCreateMp4(
                context = appContext,
                slug = model.slug,
                contentKey = contentKey,
            ) {
                SpecialEventsGuideBitmapRenderer.render(
                    category = model.category,
                    emoji = model.emoji,
                    events = model.events,
                )
            }
        } ?: run {
            respondError(call, HttpStatusCode.BadGateway, "guide_video_unavailable")
            return
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        respondFile(call, mp4, ContentType("video", "mp4"))
    }

    private data class GuideModel(
        val slug: String,
        val guideId: String,
        val category: String,
        val emoji: String,
        val events: List<com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsMerger.GuideEventRow>,
    )

    private fun guideModel(rawSlug: String): GuideModel? {
        val slug = normalizeGuideSlug(rawSlug) ?: return null
        val guideId = "dlhd-guide:$slug"
        val guide = supplementSource.dlhdGuideChannel(slug)
        val rawCategory = guide?.name?.removeSuffix(" Schedule")?.trim().orEmpty()
            .ifEmpty { slug.replace('-', ' ') }
        val category = SpecialEventCategoryEmoji.stripLeadingEmoji(rawCategory)
        val emoji = SpecialEventCategoryEmoji.forCategory(category, guide?.providerTag)
        return GuideModel(
            slug = slug,
            guideId = guideId,
            category = category,
            emoji = emoji,
            events = supplementSource.guideSchedule(guideId),
        )
    }

    private suspend fun respondFile(call: ApplicationCall, file: File, contentType: ContentType) {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        call.respondBytes(bytes, contentType)
    }

    suspend fun guideStreamLegacy(call: ApplicationCall) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        val body = HlsErrorManifest.build("Update playlist — guide channels now use schedule video")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(body.toByteArray(StandardCharsets.UTF_8), ContentType("application", "vnd.apple.mpegurl"))
    }

    private fun normalizeGuideSlug(raw: String): String? {
        val slug = raw.trim().trim('/').removeSuffix(".html").removeSuffix(".m3u8").removeSuffix(".mp4")
        if (slug.isEmpty()) return null
        if (!slug.matches(GUIDE_SLUG_RE)) return null
        return slug
    }

    private fun guideNotFoundHtml(slug: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8"><title>Guide not found</title></head>
        <body style="font-family:sans-serif;background:#0f1419;color:#e8eef5;padding:1.5rem">
        <h1>Guide not found</h1>
        <p>No schedule guide for <strong>${slug.take(80)}</strong>.</p>
        </body></html>
    """.trimIndent()

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
        private const val STREAM_TIMEOUT_MS = 45_000L
        private val GUIDE_SLUG_RE = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
    }
}
