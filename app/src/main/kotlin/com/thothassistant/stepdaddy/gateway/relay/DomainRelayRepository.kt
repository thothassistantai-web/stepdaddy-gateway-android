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

class DomainRelayRepository(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val cacheFile = File(context.applicationContext.filesDir, CACHE_FILE_NAME)

    fun loadCache(): CachedRelay? {
        if (!cacheFile.isFile) return null
        val text = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching {
            json.decodeFromString(CachedRelay.serializer(), text)
        }.getOrNull()
    }

    fun saveCache(manifest: DomainRelayManifest, sourceLabel: String, fetchedAtMs: Long) {
        val payload = CachedRelay(
            fetchedAtMs = fetchedAtMs,
            sourceLabel = sourceLabel,
            manifest = manifest,
        )
        runCatching {
            cacheFile.writeText(json.encodeToString(CachedRelay.serializer(), payload), Charsets.UTF_8)
        }
    }

    suspend fun fetchRemote(cachedVersion: Int): Result<FetchResult> {
        val errors = mutableListOf<String>()
        for ((url, label) in candidateUrls()) {
            val result = runCatching { fetchOne(url, label, cachedVersion) }
            val value = result.getOrNull()
            if (value != null) return Result.success(value)
            errors += "${label}: ${result.exceptionOrNull()?.message ?: "failed"}"
        }
        return Result.failure(IllegalStateException(errors.joinToString("; ").ifBlank { "domain-relay fetch failed" }))
    }

    private suspend fun fetchOne(url: String, label: String, cachedVersion: Int): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val text = httpClient.getText(request)
        if (text.length > DomainRelayValidator.MAX_BYTES) {
            error("response exceeds ${DomainRelayValidator.MAX_BYTES} bytes")
        }
        val manifest = DomainRelayValidator.parseAndValidate(
            text = text,
            installedVersionName = BuildConfig.VERSION_NAME,
            cachedVersion = cachedVersion,
            decode = { raw -> json.decodeFromString(DomainRelayManifest.serializer(), raw) },
        ).getOrThrow()
        return FetchResult(manifest = manifest, sourceLabel = label, raw = text)
    }

    private fun candidateUrls(): List<Pair<String, String>> {
        val raw = BuildConfig.DEFAULT_DOMAIN_RELAY_URL.trim()
        val release = BuildConfig.DEFAULT_DOMAIN_RELAY_RELEASE_URL.trim()
        return buildList {
            if (raw.isNotEmpty()) add(raw to "raw-main")
            if (release.isNotEmpty() && release != raw) add(release to "release-asset")
        }
    }

    companion object {
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val CACHE_FILE_NAME = "domain-relay-cache.json"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}

@kotlinx.serialization.Serializable
data class CachedRelay(
    val fetchedAtMs: Long,
    val sourceLabel: String,
    val manifest: DomainRelayManifest,
)

data class FetchResult(
    val manifest: DomainRelayManifest,
    val sourceLabel: String,
    val raw: String,
)
