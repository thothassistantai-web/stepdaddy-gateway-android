package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Embedded Stremio Live TV addon (protocol resources under `/stremio/`).
 *
 * Install in Stremio: `http://127.0.0.1:3000/stremio/manifest.json`
 * (or LAN base when LAN mode is enabled).
 */
class StremioRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val supplementSource: SupplementSource,
    private val addonId: String = "org.stepdaddy.stremio",
    private val addonName: String = "StepDaddy Live TV",
    private val addonVersion: String = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
    private val cacheMaxAge: Int = 300,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun manifest(call: ApplicationCall) {
        val manifest = StremioManifest(
            id = addonId,
            version = addonVersion,
            name = addonName,
            description =
                "Live TV from StepDaddy Gateway (DaddyLive, IPTV-org, Free-TV, Dulo, ntv.cx, Adult Swim)",
            logo = "https://www.stremio.com/website/stremio-logo-small.png",
            resources = listOf("catalog", "meta", "stream"),
            types = listOf("tv"),
            idPrefixes = listOf("stepdaddy:"),
            catalogs = listOf(
                StremioCatalog(
                    type = "tv",
                    id = "channels",
                    name = "StepDaddy Channels",
                    extra = listOf(StremioExtra(name = "skip")),
                ),
                StremioCatalog(
                    type = "tv",
                    id = "movies",
                    name = "Movies",
                    extra = listOf(StremioExtra(name = "skip")),
                ),
                StremioCatalog(
                    type = "tv",
                    id = "local",
                    name = "Local Channels",
                    extra = listOf(StremioExtra(name = "skip")),
                ),
            ),
            behaviorHints = StremioBehaviorHints(configurable = false),
        )
        respondJson(call, json.encodeToString(manifest))
    }

    suspend fun catalog(call: ApplicationCall) {
        val catalogId = call.parameters["id"]?.removeSuffix(".json").orEmpty().ifBlank { "channels" }
        val skip = call.request.queryParameters["skip"]?.toIntOrNull()
            ?: extraSkip(call.parameters["extra"])
            ?: 0
        val channels = withContext(Dispatchers.IO) {
            allChannels().filter { matchesCatalog(catalogId, it.group) }
        }
        val page = channels.drop(skip.coerceAtLeast(0)).take(100)
        val metas = page.map { preview(it) }
        respondJson(call, json.encodeToString(StremioMetasResponse(metas = metas)))
    }

    suspend fun meta(call: ApplicationCall) {
        val rawId = call.parameters["id"].orEmpty()
        val channelId = normalizeId(rawId) ?: run {
            respondJson(call, "{}")
            return
        }
        val channel = withContext(Dispatchers.IO) { allChannels() }
            .firstOrNull { it.id == channelId }
            ?: run {
                respondJson(call, "{}")
                return
            }
        val meta = StremioMeta(
            id = channel.stremioId,
            type = "tv",
            name = channel.name,
            poster = channel.poster,
            posterShape = "square",
            description = "Live via StepDaddy Gateway · ${channel.group}",
            background = channel.poster,
            behaviorHints = StremioMetaHints(defaultVideoId = channel.stremioId),
        )
        respondJson(call, json.encodeToString(StremioMetaResponse(meta = meta)))
    }

    suspend fun stream(call: ApplicationCall) {
        val rawId = call.parameters["id"].orEmpty()
        val channelId = normalizeId(rawId) ?: run {
            respondJson(call, json.encodeToString(StremioStreamsResponse(streams = emptyList())))
            return
        }
        val channel = withContext(Dispatchers.IO) { allChannels() }
            .firstOrNull { it.id == channelId }
            ?: run {
                respondJson(call, json.encodeToString(StremioStreamsResponse(streams = emptyList())))
                return
            }
        val base = environment.loopbackBase().trimEnd('/')
        val streamUrl = "$base/tivimate-stream/${channel.id}.m3u8"
        val stream = StremioStream(
            name = channel.name,
            title = "StepDaddy Gateway",
            description = "Live HLS (proxied MPEG-TS)",
            url = streamUrl,
            behaviorHints = StremioStreamHints(
                // Media3/ExoPlayer on Android TV — not browser MSE.
                notWebReady = true,
                bingeGroup = "stepdaddy-${channel.group}",
                proxyHeaders = StremioProxyHeaders(
                    request = mapOf(
                        "User-Agent" to GatewayConfig.TIVIMATE_USER_AGENT,
                        "Accept" to "*/*",
                    ),
                ),
            ),
        )
        respondJson(call, json.encodeToString(StremioStreamsResponse(streams = listOf(stream))))
    }

    suspend fun health(call: ApplicationCall) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondText("OK", ContentType.Text.Plain)
    }

    private fun allChannels(): List<StremioChannelRow> {
        val out = ArrayList<StremioChannelRow>(client.channels.size + supplementSource.channelCount())
        for (ch in client.channels) {
            out += StremioChannelRow(
                id = ch.id,
                name = ch.name,
                poster = ch.logo?.takeIf { it.isNotBlank() }
                    ?: "https://www.stremio.com/website/stremio-logo-small.png",
                group = ch.tags.firstOrNull()?.takeIf { it.isNotBlank() } ?: "DaddyLive",
            )
        }
        for (ch in supplementSource.channels()) {
            out += StremioChannelRow(
                id = ch.id,
                name = ch.name,
                poster = ch.logo?.takeIf { it.isNotBlank() }
                    ?: "https://www.stremio.com/website/stremio-logo-small.png",
                group = ch.groupTitle.ifBlank { "Supplement" },
            )
        }
        return out
    }

    private fun preview(ch: StremioChannelRow): StremioMetaPreview =
        StremioMetaPreview(
            id = ch.stremioId,
            type = "tv",
            name = ch.name,
            poster = ch.poster,
            posterShape = "square",
            description = ch.group,
        )

    private fun matchesCatalog(catalogId: String, group: String): Boolean {
        val g = group.lowercase()
        return when (catalogId.lowercase()) {
            "movies" -> g.contains("movie")
            "local" -> g.contains("local")
            "channels", "" -> true
            else -> true
        }
    }

    private fun normalizeId(raw: String): String? {
        val trimmed = raw.trim().removeSuffix(".json")
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("stepdaddy:") -> trimmed.removePrefix("stepdaddy:")
            else -> trimmed
        }.takeIf { it.isNotBlank() }
    }

    private fun extraSkip(extra: String?): Int? {
        if (extra.isNullOrBlank()) return null
        // Stremio may pass "skip=100" or "skip=100.json"
        val cleaned = extra.removeSuffix(".json")
        val skipPart = cleaned.split('&').firstOrNull { it.startsWith("skip=") } ?: return null
        return skipPart.removePrefix("skip=").toIntOrNull()
    }

    private suspend fun respondJson(call: ApplicationCall, body: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
        call.response.header(HttpHeaders.CacheControl, "public, max-age=$cacheMaxAge")
        call.respondText(body, ContentType.Application.Json)
    }

    private data class StremioChannelRow(
        val id: String,
        val name: String,
        val poster: String,
        val group: String,
    ) {
        val stremioId: String get() = "stepdaddy:$id"
    }
}

@Serializable
private data class StremioManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val logo: String,
    val resources: List<String>,
    val types: List<String>,
    val idPrefixes: List<String>,
    val catalogs: List<StremioCatalog>,
    val behaviorHints: StremioBehaviorHints = StremioBehaviorHints(),
)

@Serializable
private data class StremioCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<StremioExtra> = emptyList(),
)

@Serializable
private data class StremioExtra(
    val name: String,
    val isRequired: Boolean = false,
)

@Serializable
private data class StremioBehaviorHints(
    val configurable: Boolean = false,
)

@Serializable
private data class StremioMetasResponse(
    val metas: List<StremioMetaPreview>,
)

@Serializable
private data class StremioMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String,
    val posterShape: String = "square",
    val description: String? = null,
)

@Serializable
private data class StremioMetaResponse(
    val meta: StremioMeta,
)

@Serializable
private data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String,
    val posterShape: String = "square",
    val description: String? = null,
    val background: String? = null,
    val behaviorHints: StremioMetaHints = StremioMetaHints(),
)

@Serializable
private data class StremioMetaHints(
    val defaultVideoId: String? = null,
)

@Serializable
private data class StremioStreamsResponse(
    val streams: List<StremioStream>,
)

@Serializable
private data class StremioStream(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val url: String,
    val behaviorHints: StremioStreamHints = StremioStreamHints(),
)

@Serializable
private data class StremioStreamHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val proxyHeaders: StremioProxyHeaders? = null,
)

@Serializable
private data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
)
