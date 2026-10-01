package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.diagnostics.DebugDiagnosticsResponse
import com.thothassistant.stepdaddy.gateway.diagnostics.DebugMemorySnapshot
import com.thothassistant.stepdaddy.gateway.diagnostics.DebugProbeResult
import com.thothassistant.stepdaddy.gateway.diagnostics.RuntimeTuneRuntime
import com.thothassistant.stepdaddy.gateway.diagnostics.RuntimeTuneValues
import com.thothassistant.stepdaddy.gateway.diagnostics.StreamDiagnostics
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.HlsImageSegmentUnwrapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

class DebugRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val httpClient: OkHttpClient,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun diagnostics(call: ApplicationCall) {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        val used = total - free
        val payload = DebugDiagnosticsResponse(
            ok = true,
            version = BuildConfig.VERSION_NAME,
            memory = DebugMemorySnapshot(
                maxMb = max / (1024 * 1024),
                totalMb = total / (1024 * 1024),
                freeMb = free / (1024 * 1024),
                usedMb = used / (1024 * 1024),
            ),
            tune = RuntimeTuneRuntime.effective(),
            tuneStatus = RuntimeTuneRuntime.status(),
            streams = StreamDiagnostics.snapshot(),
            proxyInFlight = RuntimeTuneRuntime.contentProxyLimiter.inFlight,
            upstreamInFlight = RuntimeTuneRuntime.upstreamFetchLimiter.inFlight,
        )
        call.respondText(json.encodeToString(payload), ContentType.Application.Json)
    }

    suspend fun getConfig(call: ApplicationCall) {
        call.respondText(
            json.encodeToString(RuntimeTuneRuntime.effective()),
            ContentType.Application.Json,
        )
    }

    suspend fun putConfig(call: ApplicationCall) {
        val patch = call.receive<RuntimeTuneValues>()
        val effective = RuntimeTuneRuntime.applyPatch(patch, sourceLabel = "debug-api")
        call.respondText(json.encodeToString(effective), ContentType.Application.Json)
    }

    suspend fun resetConfig(call: ApplicationCall) {
        RuntimeTuneRuntime.clear()
        call.respondText(
            json.encodeToString(RuntimeTuneRuntime.effective()),
            ContentType.Application.Json,
        )
    }

    suspend fun resetCounters(call: ApplicationCall) {
        StreamDiagnostics.resetCounters()
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    suspend fun purgeCaches(call: ApplicationCall) {
        client.purgePlaylistCaches("debug_api")
        call.respond(HttpStatusCode.OK, mapOf("ok" to true, "purged" to "playlist_caches"))
    }

    /**
     * Server-side cold probe: resolve tivimate-stream equivalent + first media segment via /content proxy URL.
     */
    suspend fun probe(call: ApplicationCall) {
        val channelId = call.request.queryParameters["id"].orEmpty().trim()
        if (channelId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
            return
        }
        val result = withContext(Dispatchers.IO) { runProbe(channelId) }
        call.respondText(json.encodeToString(result), ContentType.Application.Json)
    }

    private suspend fun runProbe(channelId: String): DebugProbeResult {
        val t0 = System.currentTimeMillis()
        return try {
            val playlist = client.resolveStream(
                channelId,
                useProxy = true,
                apiUrl = environment.loopbackBase(),
            )
            val manifestMs = System.currentTimeMillis() - t0
            val mediaLine = playlist.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.substringBefore('|')
                .orEmpty()
            if (mediaLine.isEmpty()) {
                StreamDiagnostics.recordResolve(channelId, 200, manifestMs, ok = true, detail = "empty_media")
                return DebugProbeResult(
                    channelId = channelId,
                    manifestHttp = 200,
                    manifestMs = manifestMs,
                    ok = false,
                    detail = "manifest_ok_no_media_url",
                )
            }
            val segmentUrl = rewriteLoopback(mediaLine)
            val t1 = System.currentTimeMillis()
            val request = Request.Builder().url(segmentUrl).get().build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.bytes() ?: byteArrayOf()
                    val segmentMs = System.currentTimeMillis() - t1
                    val unwrapped = HlsImageSegmentUnwrapper.maybeUnwrap(body)
                    val ts = unwrapped.isNotEmpty() && unwrapped[0] == 0x47.toByte()
                    val ctype = if (ts) "video/mp2t" else (response.header("Content-Type") ?: "unknown")
                    val ok = response.isSuccessful && ts
                    StreamDiagnostics.recordResolve(channelId, 200, manifestMs, ok = true, detail = "probe")
                    StreamDiagnostics.recordSegment(
                        httpStatus = response.code,
                        latencyMs = segmentMs,
                        ok = ok,
                        unwrapped = unwrapped.size != body.size || HlsImageSegmentUnwrapper.looksWrapped(body),
                        contentType = ctype,
                        detail = "probe",
                    )
                    DebugProbeResult(
                        channelId = channelId,
                        manifestHttp = 200,
                        manifestMs = manifestMs,
                        segmentHttp = response.code,
                        segmentMs = segmentMs,
                        segmentBytes = unwrapped.size,
                        contentType = ctype,
                        tsMagic = ts,
                        ok = ok,
                        detail = if (ok) "ok" else "segment_not_ts",
                    )
                }
            }
        } catch (exc: Exception) {
            val ms = System.currentTimeMillis() - t0
            StreamDiagnostics.recordResolve(
                channelId,
                httpStatus = 504,
                latencyMs = ms,
                ok = false,
                detail = exc.message?.take(80).orEmpty(),
            )
            DebugProbeResult(
                channelId = channelId,
                manifestHttp = 504,
                manifestMs = ms,
                ok = false,
                detail = exc.message?.take(120).orEmpty(),
            )
        }
    }

    private fun rewriteLoopback(url: String): String {
        return try {
            val parsed = URL(url)
            if (parsed.host == "127.0.0.1" || parsed.host.equals("localhost", ignoreCase = true)) {
                URL(
                    parsed.protocol,
                    "127.0.0.1",
                    environment.port,
                    parsed.file,
                ).toString()
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }
}
