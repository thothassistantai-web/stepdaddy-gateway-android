package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves CDN Live channels from ntv.cx → cdnlivetv.tv signed HLS manifests.
 * Tokens are short-lived; callers should resolve on each play.
 */
class NtvCxCdnLiveResolver(
    private val httpClient: OkHttpClient,
) {
    data class CatalogChannel(
        val name: String,
        val regionCode: String,
        val logo: String?,
    )

    data class FetchStats(
        val catalogRows: Int = 0,
        val cdnLiveRows: Int = 0,
        val channelsAfterDedup: Int = 0,
        val resolveProbeOk: Boolean = false,
    )

    suspend fun fetchCatalog(): List<CatalogChannel> = withContext(Dispatchers.IO) {
        val text = fetchText(
            NtvCxCdnLiveConfig.CHANNELS_API,
            referer = NtvCxCdnLiveConfig.PLAYER_REFERER,
            maxBytes = NtvCxCdnLiveConfig.MAX_CHANNELS_JSON_BYTES,
        ) ?: return@withContext emptyList()
        parseCatalogJson(text)
    }

    suspend fun resolveManifestUrl(channelName: String, regionCode: String): String =
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

    suspend fun fetchManifestText(manifestUrl: String): String =
        withContext(Dispatchers.IO) {
            fetchText(manifestUrl, referer = NtvCxCdnLiveConfig.REFERER)
                ?: error("cdnlivetv manifest fetch failed")
        }

    private fun fetchText(
        url: String,
        referer: String? = null,
        maxBytes: Int = 2 * 1024 * 1024,
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
        referer?.let { builder.header("Referer", it) }
        return runCatching {
            httpClient.newCall(builder.build()).execute().use { response ->
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

    companion object {
        private const val TAG = "NtvCxCdnLiveResolver"

        private val embedTokenPattern = Regex("""/embed\?t=([^"'&\s]+)""")
        private val iframeSrcPattern = Regex("""src="(https?://[^"]+)"""")
        private val b64ChunkPattern = Regex("""var ([A-Za-z]+)='([^']+)'""")
        private val hashIdPattern = Regex("""[a-f0-9]{20,40}""")
        private val slugSanitizer = Regex("""[^a-zA-Z0-9\-]""")

        fun watchPageUrl(channelName: String, regionCode: String): String {
            val slug = cdnLiveSlug(channelName)
            val code = URLEncoder.encode(regionCode.trim(), StandardCharsets.UTF_8.name())
            return "${NtvCxCdnLiveConfig.BASE_URL}/channel-cdnlive/$slug?code=$code"
        }

        fun embedUrl(token: String): String =
            "${NtvCxCdnLiveConfig.BASE_URL}/embed?t=${token.trim()}"

        fun cdnLiveSlug(channelName: String): String =
            channelName.trim()
                .replace(Regex("""\s+"""), "-")
                .replace(slugSanitizer, "")

        fun cdnLiveKey(channelName: String, regionCode: String): String =
            "${channelName.trim()}|${regionCode.trim()}"

        fun parseCatalogJson(jsonText: String): List<CatalogChannel> {
            val root = runCatching {
                Json.parseToJsonElement(jsonText).jsonObject
            }.getOrElse { return emptyList() }
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
            val channels = root["channels"]?.jsonArray ?: return emptyList()
            val out = ArrayList<CatalogChannel>(channels.size)
            for (element in channels) {
                val row = element.jsonObject
                if (row["server"]?.jsonPrimitive?.content != "cdnlive") continue
                val name = row["channel_name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) continue
                val code = row["channel_code"]?.jsonPrimitive?.content?.trim().orEmpty().ifEmpty { "us" }
                val logo = absoluteImageUrl(row["channel_image"]?.jsonPrimitive?.content)
                out += CatalogChannel(name = name, regionCode = code, logo = logo)
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(NtvCxCdnLiveConfig.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(NtvCxCdnLiveConfig.FETCH_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
