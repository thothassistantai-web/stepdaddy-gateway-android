package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Resolves 24/7 channels from ntv.cx:
 * - cdnlive (Titan) → cdnlivetv.tv signed HLS
 * - hesgoales (Falcon) → hesgoaler.com tokenized HLS
 *
 * Tokens are short-lived; callers should resolve on each play.
 */
class NtvCxCdnLiveResolver(
    private val httpClient: OkHttpClient,
    private val catalogStore: NtvCxCatalogStore? = null,
) {
    data class CatalogChannel(
        val server: String,
        val name: String,
        val regionCode: String,
        val logo: String?,
        /** hesgoaler.com stream.php URL from catalog. */
        val streamPageUrl: String? = null,
    )

    data class NtvKeyParts(
        val server: String,
        val name: String,
        val extra: String,
    )

    data class FetchStats(
        val catalogRows: Int = 0,
        val cdnLiveRows: Int = 0,
        val hesgoalesRows: Int = 0,
        val channelsAfterDedup: Int = 0,
        val resolveProbeOk: Boolean = false,
    )

    suspend fun fetchCatalog(): List<CatalogChannel> = withContext(Dispatchers.IO) {
        val backoffsMs = longArrayOf(0L, 1_500L, 3_000L)
        repeat(NtvCxCdnLiveConfig.CATALOG_FETCH_RETRIES) { attempt ->
            if (attempt > 0) {
                delay(backoffsMs[attempt.coerceAtMost(backoffsMs.lastIndex)])
            }
            val text = fetchCatalogText(
                readTimeoutMs = NtvCxCdnLiveConfig.CATALOG_FETCH_TIMEOUT_MS,
            )
            if (text != null) {
                val rows = parseCatalogJson(text)
                if (rows.isNotEmpty()) {
                    catalogStore?.writeRaw(text)
                    if (attempt > 0) {
                        Log.i(TAG, "ntv.cx catalog fetched on retry ${attempt + 1} (${rows.size} rows)")
                    }
                    return@withContext rows
                }
            }
            Log.w(TAG, "ntv.cx catalog attempt ${attempt + 1} failed or empty")
        }
        val cached = catalogStore?.loadCatalog().orEmpty()
        if (cached.isNotEmpty()) {
            if (cached.size < 50) {
                Log.w(TAG, "ntv.cx using fallback catalog (${cached.size} rows)")
            }
            return@withContext cached
        }
        Log.w(TAG, "ntv.cx catalog unavailable — no network, disk, or bundled rows")
        emptyList()
    }

    suspend fun resolveManifestUrl(key: String): String = withContext(Dispatchers.IO) {
        val parts = parseNtvKey(key) ?: error("ntv_key_invalid")
        when (parts.server) {
            "cdnlive" -> resolveCdnLiveManifestUrl(parts.name, parts.extra)
            "hesgoales" -> resolveHesgoalesManifestUrl(parts.extra)
            else -> error("ntv_server_unsupported")
        }
    }

    fun refererForKey(key: String): String {
        val server = parseNtvKey(key)?.server.orEmpty()
        return when (server) {
            "hesgoales" -> NtvCxCdnLiveConfig.HESGOALES_REFERER
            else -> NtvCxCdnLiveConfig.REFERER
        }
    }

    suspend fun resolveCdnLiveManifestUrl(channelName: String, regionCode: String): String =
        withContext(Dispatchers.IO) {
            val watchUrl = watchPageUrl(channelName, regionCode)
            val watchHtml = fetchText(watchUrl, referer = NtvCxCdnLiveConfig.PLAYER_REFERER)
                ?: error("ntv watch page failed")
            val embedToken = extractEmbedToken(watchHtml)
                ?: error("ntv embed token missing")
            val embedHtml = fetchText(
                embedUrl(embedToken),
                referer = watchUrl,
            ) ?: error("ntv embed page failed")
            val playerUrl = extractIframeSrc(embedHtml)
                ?: error("ntv player iframe missing")
            val playerHtml = fetchText(playerUrl, referer = NtvCxCdnLiveConfig.PLAYER_REFERER)
                ?: error("cdnlivetv player failed")
            parsePlayerM3u8(playerHtml)
                ?: error("cdnlivetv m3u8 missing")
        }

    suspend fun resolveHesgoalesManifestUrl(streamPageUrl: String): String =
        withContext(Dispatchers.IO) {
            val pageUrl = streamPageUrl.trim().ifEmpty { error("hesgoales_url_missing") }
            val html = fetchText(pageUrl, referer = NtvCxCdnLiveConfig.HESGOALES_REFERER)
                ?: error("hesgoales page failed")
            val src = extractHesgoalesSrc(html) ?: error("hesgoales src missing")
            val channelId = extractHesgoalesChannelId(html, pageUrl) ?: error("hesgoales channel id missing")
            val token = refreshHesgoalesToken(pageUrl, channelId) ?: error("hesgoales token missing")
            appendToken(src, token)
        }

    suspend fun fetchManifestText(manifestUrl: String, referer: String): String =
        withContext(Dispatchers.IO) {
            fetchText(manifestUrl, referer = referer)
                ?: error("ntv manifest fetch failed")
        }

    private fun fetchCatalogText(readTimeoutMs: Long): String? =
        fetchText(
            url = NtvCxCdnLiveConfig.CHANNELS_API,
            referer = NtvCxCdnLiveConfig.PLAYER_REFERER,
            maxBytes = NtvCxCdnLiveConfig.MAX_CHANNELS_JSON_BYTES,
            readTimeoutMs = readTimeoutMs,
            userAgent = NtvCxCdnLiveConfig.CATALOG_USER_AGENT,
            connectionClose = true,
        )

    private fun fetchText(
        url: String,
        referer: String? = null,
        maxBytes: Int = 2 * 1024 * 1024,
        readTimeoutMs: Long = NtvCxCdnLiveConfig.FETCH_TIMEOUT_MS,
        userAgent: String = SupplementConfig.USER_AGENT,
        connectionClose: Boolean = false,
    ): String? {
        val client = if (readTimeoutMs == NtvCxCdnLiveConfig.FETCH_TIMEOUT_MS) {
            httpClient
        } else {
            httpClient.newBuilder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(readTimeoutMs + 10_000L, TimeUnit.MILLISECONDS)
                .build()
        }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .get()
        if (connectionClose) {
            builder.header("Connection", "close")
        }
        referer?.let { builder.header("Referer", it) }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.bytes()
                if (bytes.size > maxBytes) return null
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrElse { exc ->
            Log.w(TAG, "fetch failed $url: ${exc.message}")
            null
        }
    }

    private fun postJson(url: String, json: String, referer: String?): String? {
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .post(body)
        referer?.let { builder.header("Referer", it) }
        return runCatching {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrElse { exc ->
            Log.w(TAG, "post failed $url: ${exc.message}")
            null
        }
    }

    companion object {
        private const val TAG = "NtvCxCdnLiveResolver"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val embedTokenPattern = Regex("""/embed\?t=([^"'&\s]+)""")
        private val iframeSrcPattern = Regex("""src="(https?://[^"]+)"""")
        private val b64ChunkPattern = Regex("""var ([A-Za-z]+)='([^']+)'""")
        private val hashIdPattern = Regex("""[a-f0-9]{20,40}""")
        private val slugSanitizer = Regex("""[^a-zA-Z0-9\-]""")
        private val hesgoalesSrcPattern = Regex("""src:\s*"([^"]+\.m3u8[^"]*)"""")
        private val hesgoalesChannelPattern = Regex("""ch:\s*"([^"]+)"""")
        private val hesgoalesUrlChPattern = Regex("""[?&]ch=([^&]+)""")

        fun watchPageUrl(channelName: String, regionCode: String): String {
            val slug = channelSlug(channelName)
            val code = URLEncoder.encode(regionCode.trim(), StandardCharsets.UTF_8.name())
            return "${NtvCxCdnLiveConfig.BASE_URL}/channel-cdnlive/$slug?code=$code"
        }

        fun embedUrl(token: String): String =
            "${NtvCxCdnLiveConfig.BASE_URL}/embed?t=${token.trim()}"

        fun channelSlug(channelName: String): String =
            channelName.trim()
                .replace(Regex("""\s+"""), "-")
                .replace(slugSanitizer, "")
                .lowercase()

        /** @deprecated use [channelSlug] */
        fun cdnLiveSlug(channelName: String): String = channelSlug(channelName)

        fun ntvKey(
            server: String,
            name: String,
            regionCode: String,
            streamPageUrl: String? = null,
        ): String = when (server) {
            "hesgoales" -> "$server|${name.trim()}|${streamPageUrl.orEmpty().trim()}"
            else -> "$server|${name.trim()}|${regionCode.trim().ifEmpty { "us" }}"
        }

        fun parseNtvKey(key: String): NtvKeyParts? {
            val parts = key.split("|", limit = 3)
            if (parts.size < 3) return null
            val server = parts[0].trim()
            val name = parts[1].trim()
            val extra = parts[2].trim()
            if (server.isEmpty() || name.isEmpty()) return null
            if (server == "hesgoales" && extra.isEmpty()) return null
            return NtvKeyParts(server = server, name = name, extra = extra)
        }

        /** @deprecated use [ntvKey] */
        fun cdnLiveKey(channelName: String, regionCode: String): String =
            ntvKey("cdnlive", channelName, regionCode)

        fun parseCatalogJson(jsonText: String): List<CatalogChannel> {
            val root = runCatching {
                Json.parseToJsonElement(jsonText).jsonObject
            }.getOrElse { return emptyList() }
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
            val channels = root["channels"]?.jsonArray ?: return emptyList()
            val out = ArrayList<CatalogChannel>(channels.size)
            for (element in channels) {
                val row = element.jsonObject
                val server = row["server"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (server != "cdnlive" && server != "hesgoales") continue
                val name = row["channel_name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) continue
                val code = row["channel_code"]?.jsonPrimitive?.content?.trim().orEmpty().ifEmpty { "us" }
                val logo = absoluteImageUrl(row["channel_image"]?.jsonPrimitive?.content)
                val streamPageUrl = if (server == "hesgoales") {
                    row["channel_url"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                if (server == "hesgoales" && streamPageUrl.isNullOrBlank()) continue
                out += CatalogChannel(
                    server = server,
                    name = name,
                    regionCode = code,
                    logo = logo,
                    streamPageUrl = streamPageUrl,
                )
            }
            return out
        }

        fun extractEmbedToken(html: String): String? =
            embedTokenPattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        fun extractIframeSrc(html: String): String? =
            iframeSrcPattern.find(html.replace("&amp;", "&"))
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        fun extractHesgoalesSrc(html: String): String? =
            hesgoalesSrcPattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        fun extractHesgoalesChannelId(html: String, streamPageUrl: String): String? {
            hesgoalesChannelPattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            return hesgoalesUrlChPattern.find(streamPageUrl)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        fun refreshHesgoalesToken(streamPageUrl: String, channelId: String, postJson: (String, String) -> String?): String? {
            val json = """{"channel":"${channelId.trim()}","current_token":""}"""
            val response = postJson(streamPageUrl, json) ?: return null
            val root = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
            return root["token"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun appendToken(manifestSrc: String, token: String): String {
            val base = manifestSrc.substringBefore('?').trim()
            return "$base?token=$token"
        }

        fun parsePlayerM3u8(playerHtml: String): String? {
            val decodedChunks = linkedMapOf<String, String>()
            for (match in b64ChunkPattern.findAll(playerHtml)) {
                val value = decodeB64Chunk(match.groupValues[2]) ?: continue
                decodedChunks[match.groupValues[1]] = value
            }
            val hashId = decodedChunks.values.firstOrNull { hashIdPattern.matches(it) } ?: return null
            val token = decodedChunks.values.firstOrNull { it.startsWith("?token=") } ?: return null
            return "https://cdnlivetv.tv/secure/api/v1/$hashId/playlist.m3u8$token"
        }

        fun absoluteImageUrl(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            return "${NtvCxCdnLiveConfig.BASE_URL}$trimmed"
        }

        private fun decodeB64Chunk(raw: String): String? {
            var normalized = raw.trim().replace('~', '=')
            while (normalized.length % 4 != 0) {
                normalized += "="
            }
            return runCatching {
                String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
            }.getOrNull()
        }

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(NtvCxCdnLiveConfig.CATALOG_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(NtvCxCdnLiveConfig.CATALOG_FETCH_TIMEOUT_MS + 15_000L, TimeUnit.MILLISECONDS)
                .build()
    }

    private fun refreshHesgoalesToken(streamPageUrl: String, channelId: String): String? =
        refreshHesgoalesToken(streamPageUrl, channelId) { url, json ->
            postJson(url, json, NtvCxCdnLiveConfig.HESGOALES_REFERER)
        }
}
