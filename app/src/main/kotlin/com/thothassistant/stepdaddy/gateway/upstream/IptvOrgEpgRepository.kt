package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches iptv-org FAST-provider XMLTV guides for supplement EPG merge.
 */
class IptvOrgEpgRepository(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "supplement/iptv-org-epg").also { it.mkdirs() }
    private val mergedFile = File(dir, "fast-us.xml.gz")
    private val metaFile = File(dir, "meta.txt")

    fun mergedGuideFile(): File? {
        copyBundledAssetIfNeeded()
        return mergedFile.takeIf { it.exists() && it.length() > 0L }
    }

    fun isStale(): Boolean {
        if (!mergedFile.exists()) return true
        val syncedAt = metaFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - syncedAt > IptvOrgEpgConfig.GUIDE_CACHE_TTL_MS
    }

    fun refresh(remoteUrl: String?) {
        val url = remoteUrl?.trim()?.takeIf { it.startsWith("http") }
        if (url.isNullOrBlank()) {
            copyBundledAssetIfNeeded()
            return
        }
        if (!isStale() && mergedFile.exists()) return
        val ok = downloadToFile(url, mergedFile, IptvOrgEpgConfig.MAX_GUIDE_BYTES)
        if (ok) {
            metaFile.writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "iptv-org FAST EPG cached: ${mergedFile.length()} bytes")
        } else {
            Log.w(TAG, "iptv-org FAST EPG download failed — using bundled cache if any")
            copyBundledAssetIfNeeded()
        }
    }

    private fun copyBundledAssetIfNeeded() {
        if (mergedFile.exists() && mergedFile.length() > 0L) return
        runCatching {
            appContext.assets.open(BUNDLED_ASSET).use { input ->
                mergedFile.outputStream().use { output -> input.copyTo(output) }
            }
            metaFile.writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "Copied bundled iptv-org FAST EPG (${mergedFile.length()} bytes)")
        }.onFailure { exc ->
            Log.d(TAG, "No bundled iptv-org FAST EPG: ${exc.message}")
        }
    }

    private fun downloadToFile(url: String, target: File, maxBytes: Int): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", IptvOrgEpgConfig.USER_AGENT)
            .get()
            .build()
        val tmp = File(target.parentFile, "${target.name}.part")
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                var total = 0L
                tmp.outputStream().use { sink ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) error("iptv_org_epg_too_large")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    companion object {
        private const val TAG = "IptvOrgEpgRepo"
        const val BUNDLED_ASSET = "epg/iptv_org_fast_epg.xml.gz"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(IptvOrgEpgConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(IptvOrgEpgConfig.DOWNLOAD_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
