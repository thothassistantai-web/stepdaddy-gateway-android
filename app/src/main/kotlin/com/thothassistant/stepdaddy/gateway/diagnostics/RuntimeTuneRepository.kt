package com.thothassistant.stepdaddy.gateway.diagnostics

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class RuntimeTuneRepository(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val cacheFile = File(context.filesDir, "runtime-tune-cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class CachedPack(
        val pack: RuntimeTunePack,
        val sourceLabel: String,
        val fetchedAtMs: Long,
    )

    data class FetchResult(
        val pack: RuntimeTunePack,
        val sourceLabel: String,
    )

    fun loadCache(): CachedPack? {
        if (!cacheFile.isFile) return null
        return runCatching {
            val text = cacheFile.readText()
            val envelope = json.decodeFromString<CacheEnvelope>(text)
            CachedPack(envelope.pack, envelope.sourceLabel, envelope.fetchedAtMs)
        }.getOrElse {
            Log.w(TAG, "runtime-tune cache read failed: ${it.message}")
            null
        }
    }

    fun saveCache(pack: RuntimeTunePack, sourceLabel: String, fetchedAtMs: Long) {
        runCatching {
            val envelope = CacheEnvelope(pack, sourceLabel, fetchedAtMs)
            cacheFile.writeText(json.encodeToString(envelope))
        }.onFailure {
            Log.w(TAG, "runtime-tune cache write failed: ${it.message}")
        }
    }

    fun fetchRemote(): Result<FetchResult> {
        val urls = listOf(
            BuildConfig.DEFAULT_RUNTIME_TUNE_URL.trim(),
            BuildConfig.DEFAULT_RUNTIME_TUNE_RELEASE_URL.trim(),
        ).filter { it.isNotEmpty() }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${response.code} for $url")
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.length > 32_768) {
                        lastError = IllegalStateException("runtime-tune too large")
                        return@use
                    }
                    val pack = json.decodeFromString<RuntimeTunePack>(body)
                    if (pack.version <= 0) {
                        lastError = IllegalStateException("runtime-tune version must be > 0")
                        return@use
                    }
                    return Result.success(FetchResult(pack, url.substringAfterLast('/').ifBlank { url }))
                }
            } catch (exc: Exception) {
                lastError = exc
            }
        }
        return Result.failure(lastError ?: IllegalStateException("runtime-tune fetch failed"))
    }

    @kotlinx.serialization.Serializable
    private data class CacheEnvelope(
        val pack: RuntimeTunePack,
        val sourceLabel: String,
        val fetchedAtMs: Long,
    )

    companion object {
        private const val TAG = "RuntimeTuneRepo"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
