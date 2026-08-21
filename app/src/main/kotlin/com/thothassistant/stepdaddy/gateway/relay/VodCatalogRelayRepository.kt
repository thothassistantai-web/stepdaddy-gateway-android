package com.thothassistant.stepdaddy.gateway.relay

import android.content.Context
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.upstream.getText
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class VodCatalogRelayRepository(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val cacheFile = File(context.applicationContext.filesDir, CACHE_FILE_NAME)

    fun loadCache(): CachedVodRelay? {
        if (!cacheFile.isFile) return null
        val text = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(CachedVodRelay.serializer(), text) }.getOrNull()
    }

    fun saveCache(manifest: VodCatalogRelayManifest, sourceLabel: String, fetchedAtMs: Long) {
        val payload = CachedVodRelay(fetchedAtMs, sourceLabel, manifest)
        runCatching {
            cacheFile.writeText(json.encodeToString(CachedVodRelay.serializer(), payload), Charsets.UTF_8)
        }
    }

    suspend fun fetchRemote(cachedVersion: Int): Result<VodFetchResult> {
        val errors = mutableListOf<String>()
        for ((url, label) in candidateUrls()) {
            val result = runCatching { fetchOne(url, label, cachedVersion) }
            val value = result.getOrNull()
            if (value != null) return Result.success(value)
            errors += "${label}: ${result.exceptionOrNull()?.message ?: "failed"}"
        }
        return Result.failure(
            IllegalStateException(errors.joinToString("; ").ifBlank { "vod-catalog-relay fetch failed" }),
        )
    }

    private suspend fun fetchOne(url: String, label: String, cachedVersion: Int): VodFetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val text = httpClient.getText(request)
        if (text.length > VodCatalogRelayValidator.MAX_BYTES) {
            error("response exceeds ${VodCatalogRelayValidator.MAX_BYTES} bytes")
        }
        val manifest = VodCatalogRelayValidator.parseAndValidate(
            text = text,
            installedVersionName = BuildConfig.VERSION_NAME,
            cachedVersion = cachedVersion,
            decode = { raw -> json.decodeFromString(VodCatalogRelayManifest.serializer(), raw) },
        ).getOrThrow()
        return VodFetchResult(manifest, label)
    }

    private fun candidateUrls(): List<Pair<String, String>> {
        val raw = BuildConfig.DEFAULT_VOD_CATALOG_RELAY_URL.trim()
        val release = BuildConfig.DEFAULT_VOD_CATALOG_RELAY_RELEASE_URL.trim()
        return buildList {
            if (raw.isNotEmpty()) add(raw to "raw-main")
            if (release.isNotEmpty() && release != raw) add(release to "release-asset")
        }
    }

    companion object {
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val CACHE_FILE_NAME = "vod-catalog-relay-cache.json"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}

@kotlinx.serialization.Serializable
data class CachedVodRelay(
    val fetchedAtMs: Long,
    val sourceLabel: String,
    val manifest: VodCatalogRelayManifest,
)

data class VodFetchResult(
    val manifest: VodCatalogRelayManifest,
    val sourceLabel: String,
)
