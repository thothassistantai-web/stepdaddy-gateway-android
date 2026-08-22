package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Resolves dulo.cx Live TV playback:
 * 1. Public catalog JSON (`/api/live-tv/channels`)
 * 2. Optional session cookie (`/api/session`)
 * 3. `POST /api/live-tv/playback-session` → `/live-gateway/…` HLS (requires bearer JWT)
 */
class DuloCxLiveResolver(
    private val httpClient: OkHttpClient,
    private val accessTokenProvider: () -> String = { "" },
) {
    data class CatalogChannel(
        val id: String,
        val name: String,
        val category: String,
        val logoUrl: String?,
        val epgSourceUrl: String?,
        val supporterOnly: Boolean,
        val playable: Boolean,
        val sortOrder: Int,
    )

    data class FetchStats(
        val catalogRows: Int = 0,
        val playableRows: Int = 0,
        val supporterSkipped: Int = 0,
        val channelsAfterDedup: Int = 0,
        val catalogFetchOk: Boolean = false,
        val resolveProbeOk: Boolean = false,
        val authConfigured: Boolean = false,
    )

    suspend fun fetchCatalog(): List<CatalogChannel> = withContext(Dispatchers.IO) {
        val text = fetchText(
            url = DuloCxLiveConfig.CHANNELS_URL,
            maxBytes = DuloCxLiveConfig.MAX_CHANNELS_JSON_BYTES,
        ) ?: return@withContext emptyList()
        parseCatalogJson(text)
    }

    suspend fun resolveManifestUrl(channelId: String): String = withContext(Dispatchers.IO) {
        val id = channelId.trim()
        if (id.isEmpty()) error("dulo_channel_id_missing")
        val token = accessTokenProvider().trim()
        if (token.isEmpty()) error("dulo_auth_required")

        ensureSessionCookie()
        activateDevice(token)
        val playbackUrl = mintPlaybackUrl(token, id)
        if (!playbackUrl.contains("/live-gateway/")) {
            error("dulo_playback_url_invalid")
        }
        val absolute = if (playbackUrl.startsWith("http")) {
            playbackUrl
        } else {
            DuloCxLiveConfig.SITE_ORIGIN.trimEnd('/') + playbackUrl
        }
        // Prefer playlist.m3u8 if the gateway returned a segment path.
        normalizePlaylistUrl(absolute)
    }

    suspend fun fetchManifestText(manifestUrl: String): String = withContext(Dispatchers.IO) {
        fetchText(manifestUrl, referer = DuloCxLiveConfig.REFERER)
            ?: error("dulo_manifest_fetch_failed")
    }

    fun parseCatalogJson(text: String): List<CatalogChannel> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val arr = root["channels"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.trim().orEmpty()
            val name = obj.string("name")?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
            CatalogChannel(
                id = id,
                name = name,
                category = obj.string("category")?.trim().orEmpty().ifEmpty { "entertainment" },
                logoUrl = obj.string("logo_url")?.trim()?.takeIf { it.startsWith("http") },
                epgSourceUrl = obj.string("epg_source_url")?.trim()?.takeIf { it.startsWith("http") },
                supporterOnly = obj.boolean("supporter_only") == true,
                playable = obj.boolean("playable") != false,
                sortOrder = obj.int("sort_order") ?: 0,
            )
        }
    }

    private fun ensureSessionCookie() {
        fetchText(DuloCxLiveConfig.SESSION_URL, referer = DuloCxLiveConfig.REFERER)
    }

    private fun activateDevice(token: String) {
        val body = """{"deviceFingerprint":"${deviceFingerprint()}"}"""
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(DuloCxLiveConfig.ACTIVATE_DEVICE_URL)
            .header("User-Agent", DuloCxLiveConfig.USER_AGENT)
            .header("Origin", DuloCxLiveConfig.ORIGIN)
            .header("Referer", DuloCxLiveConfig.REFERER)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { /* best-effort */ }
        }.onFailure { Log.w(TAG, "dulo activate-device failed", it) }
    }

    private fun mintPlaybackUrl(token: String, channelId: String): String {
        val body =
            """{"deviceFingerprint":"${deviceFingerprint()}","channelId":"$channelId"}"""
                .toRequestBody(JSON)
        val request = Request.Builder()
            .url(DuloCxLiveConfig.PLAYBACK_SESSION_URL)
            .header("User-Agent", DuloCxLiveConfig.USER_AGENT)
            .header("Origin", DuloCxLiveConfig.ORIGIN)
            .header("Referer", DuloCxLiveConfig.REFERER)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching {
                    Json.parseToJsonElement(text).jsonObject.string("error")
                }.getOrNull()
                error(err ?: "dulo_playback_http_${response.code}")
            }
            val obj = Json.parseToJsonElement(text).jsonObject
            return obj.string("playbackUrl")?.trim().orEmpty()
                .ifEmpty { error("dulo_playback_url_missing") }
        }
    }

    private fun fetchText(
        url: String,
        referer: String? = DuloCxLiveConfig.REFERER,
        maxBytes: Int = 512 * 1024,
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DuloCxLiveConfig.USER_AGENT)
            .header("Origin", DuloCxLiveConfig.ORIGIN)
            .apply {
                referer?.let { header("Referer", it) }
            }
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "dulo fetch failed ${response.code} $url")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty() || bytes.size > maxBytes) return null
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrElse { exc ->
            Log.w(TAG, "dulo fetch error $url", exc)
            null
        }
    }

    private fun deviceFingerprint(): String {
        // Stable-enough per process; dulo binds Live TV sessions to a device id.
        return DEVICE_FP
    }

    private fun normalizePlaylistUrl(url: String): String {
        if (url.contains(".m3u8", ignoreCase = true)) return url
        if (url.contains(".ts", ignoreCase = true)) {
            val replaced = url.replace(Regex("/\\d+\\.ts(\\?.*)?$", RegexOption.IGNORE_CASE), "/playlist.m3u8$1")
            if (replaced != url) return replaced
            val slash = url.lastIndexOf('/')
            if (slash > 0) return url.substring(0, slash + 1) + "playlist.m3u8"
        }
        return url
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: this[key]?.jsonPrimitive?.contentOrNull?.let {
                when (it.lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    companion object {
        private const val TAG = "DuloCxLiveResolver"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val DEVICE_FP = "stepdaddy-gw-" + UUID.randomUUID().toString().take(12)

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(DuloCxLiveConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DuloCxLiveConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DuloCxLiveConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(DuloCxLiveConfig.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
