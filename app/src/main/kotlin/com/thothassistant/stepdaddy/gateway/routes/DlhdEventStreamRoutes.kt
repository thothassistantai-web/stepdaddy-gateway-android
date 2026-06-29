package com.thothassistant.stepdaddy.gateway.routes

import android.content.Context
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventActiveMirrorStore
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamHealth
import com.thothassistant.stepdaddy.gateway.upstream.DlhdEventStreamResolver
import com.thothassistant.stepdaddy.gateway.upstream.GuideScheduleHlsManifest
import com.thothassistant.stepdaddy.gateway.upstream.GuideScheduleMediaCache
import com.thothassistant.stepdaddy.gateway.upstream.HlsErrorManifest
import com.thothassistant.stepdaddy.gateway.upstream.M3u8Rewriter
import com.thothassistant.stepdaddy.gateway.upstream.MirrorHlsManifest
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventCategoryEmoji
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventMirrorRanker
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsGuideBitmapRenderer
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsGuideHtmlRenderer
import com.thothassistant.stepdaddy.gateway.upstream.SpecialEventsMirrorHealth
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
    private val client: DaddyLiveClient,
    private val resolver: DlhdEventStreamResolver = DlhdEventStreamResolver(),
    private val activeMirrorStore: DlhdEventActiveMirrorStore = supplementSource.dlhdEventActiveMirrorStore(),
) {
    private val appContext = context.applicationContext
    private val guideMediaCache = GuideScheduleMediaCache(appContext)

    fun activeMirrorStore(): DlhdEventActiveMirrorStore = activeMirrorStore

    suspend fun eventStreamMaster(call: ApplicationCall, token: String) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.dlhdEventChannel(token)
                ?: error("dlhd_event_not_found")
            val mirrors = SpecialEventsMirrorHealth.mirrorsFor(supplement)
            if (mirrors.isEmpty()) error("dlhd_event_key_missing")

            val hot = SpecialEventMirrorRanker.rankMirrors(mirrors).hot
            val variantCount = hot.size.coerceAtLeast(1)
            val body = if (variantCount <= 1) {
                withContext(Dispatchers.IO) {
                    withTimeout(STREAM_TIMEOUT_MS) {
                        resolveMirrorPlaylist(supplement, mirrors, 0)
                    }
                }
            } else {
                MirrorHlsManifest.build(
                    baseUrl = environment.loopbackBase(),
                    eventToken = token,
                    mirrorCount = variantCount,
                    labels = hot.map { it.label },
                )
            }
            activeMirrorStore.recordActive(token, 0)
            supplementSource.recordDlhdEventStreamHealth(
                token,
                DlhdEventStreamHealth.ProbeResult.healthy(),
            )
            respondPlaylist(call, body)
        } catch (_: TimeoutCancellationException) {
            recordUnhealthy(token, "upstream_timeout")
            respondError(
                call,
                HttpStatusCode.GatewayTimeout,
                "dlhd event upstream timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            recordUnhealthy(token, exc.message ?: "upstream_error")
            val transient = exc.message?.contains("timeout", ignoreCase = true) == true
            respondError(
                call,
                if (transient) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
                exc.message ?: "dlhd_event_upstream_error",
                retryAfter = if (transient) "3" else null,
            )
        }
    }

    suspend fun eventMirrorStream(call: ApplicationCall, token: String, mirrorIndex: Int) {
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType("application", "vnd.apple.mpegurl"))
            return
        }
        try {
            val supplement = supplementSource.dlhdEventChannel(token)
                ?: error("dlhd_event_not_found")
            val mirrors = SpecialEventsMirrorHealth.mirrorsFor(supplement)
            if (mirrors.isEmpty()) error("dlhd_event_key_missing")

            val playlist = withContext(Dispatchers.IO) {
                withTimeout(STREAM_TIMEOUT_MS) {
                    resolveMirrorWithFailover(supplement, mirrors, mirrorIndex)
                }
            }
            respondPlaylist(call, playlist)
        } catch (_: TimeoutCancellationException) {
            recordUnhealthy(token, "upstream_timeout")
            respondError(
                call,
                HttpStatusCode.GatewayTimeout,
                "dlhd event mirror timeout — retry shortly",
                retryAfter = "3",
            )
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            recordUnhealthy(token, exc.message ?: "mirror_error")
            respondError(
                call,
                HttpStatusCode.BadGateway,
                exc.message ?: "dlhd_event_mirror_error",
            )
        }
    }

    /** Legacy route — delegates to consolidated master/failover handler. */
    suspend fun eventStream(call: ApplicationCall, token: String) {
        eventStreamMaster(call, token)
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

    private suspend fun resolveMirrorWithFailover(
        supplement: SupplementChannel,
        mirrors: List<DlhdEventMirror>,
        preferredIndex: Int,
    ): String {
        val ordered = buildFailoverOrder(mirrors, preferredIndex)
        var lastError: Exception? = null
        for ((index, _) in ordered) {
            runCatching {
                val playlist = resolveMirrorPlaylist(supplement, mirrors, index)
                val eventKey = supplement.dlhdEventKey ?: supplement.id.removePrefix("dlhd-event:")
                activeMirrorStore.recordActive(eventKey, index)
                supplementSource.recordDlhdEventStreamHealth(
                    eventKey,
                    DlhdEventStreamHealth.ProbeResult.healthy(),
                )
                return playlist
            }.onFailure { exc ->
                if (exc is CancellationException) throw exc
                lastError = exc as? Exception ?: Exception(exc.message)
            }
        }
        throw lastError ?: IllegalStateException("all_mirrors_failed")
    }

    private fun buildFailoverOrder(
        mirrors: List<DlhdEventMirror>,
        preferredIndex: Int,
    ): List<Pair<Int, DlhdEventMirror>> {
        if (mirrors.isEmpty()) return emptyList()
        val start = preferredIndex.coerceIn(0, mirrors.lastIndex)
        val indices = (start until mirrors.size) + (0 until start)
        return indices.map { it to mirrors[it] }
    }

    private suspend fun resolveMirrorPlaylist(
        supplement: SupplementChannel,
        mirrors: List<DlhdEventMirror>,
        mirrorIndex: Int,
    ): String {
        val mirror = mirrors.getOrNull(mirrorIndex)
            ?: error("mirror_index_out_of_range")
        val key = mirror.streamKey.trim()
        if (key.isEmpty()) error("dlhd_event_key_missing")

        if (key.startsWith("tv|", ignoreCase = true)) {
            val channelId = key.substringAfter("|").trim()
            if (channelId.isEmpty()) error("missing_tv_channel_id")
            return client.resolveStream(
                channelId = channelId,
                useProxy = true,
                apiUrl = environment.loopbackBase(),
            )
        }

        val referer = mirror.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: DlhdEventStreamResolver.EMBED_REFERER
        val manifestUrl = resolver.resolveManifestUrl(key, referer)
            ?: error("dlhd_event_manifest_unresolved")
        val manifestText = resolver.fetchManifestText(manifestUrl, referer)
            ?: error("dlhd_event_manifest_fetch_failed")
        val origin = mirror.origin?.trim()?.takeIf { it.isNotEmpty() }
            ?: supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
            ?: referer.trimEnd('/')
        return M3u8Rewriter.rewrite(
            m3u8Text = manifestText,
            m3u8Url = manifestUrl,
            refererHost = origin,
            useProxy = false,
            apiUrl = environment.loopbackBase(),
            preferLighterVariant = true,
        )
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

    private suspend fun respondPlaylist(call: ApplicationCall, playlist: String) {
        val bytes = playlist.toByteArray(StandardCharsets.UTF_8)
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(bytes, ContentType("application", "vnd.apple.mpegurl"))
    }

    private fun recordUnhealthy(token: String, reason: String) {
        supplementSource.recordDlhdEventStreamHealth(
            token,
            DlhdEventStreamHealth.ProbeResult.unhealthy(reason),
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
