package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches iptv-org FAST-provider XMLTV guides for supplement EPG merge.
 */
class IptvOrgEpgRepository(
    private val assetOpener: (String) -> InputStream,
    filesDir: File,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    constructor(
        context: Context,
        httpClient: OkHttpClient = defaultClient(),
    ) : this(
        assetOpener = { assetPath -> context.applicationContext.assets.open(assetPath) },
        filesDir = context.applicationContext.filesDir,
        httpClient = httpClient,
    )

    private val dir = File(filesDir, "supplement/iptv-org-epg").also { it.mkdirs() }
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
            logInfo("iptv-org FAST EPG cached: ${mergedFile.length()} bytes")
        } else {
            logWarn("iptv-org FAST EPG download failed — using bundled cache if any")
            copyBundledAssetIfNeeded()
        }
    }

    private fun copyBundledAssetIfNeeded() {
        if (mergedFile.exists() && mergedFile.length() > 0L) return
        for (assetPath in BUNDLED_ASSET_PATHS) {
            val copied = runCatching { copyBundledAsset(assetPath) }
                .onFailure { exc -> logDebug("Bundled EPG asset $assetPath unavailable: ${exc.message}") }
                .getOrDefault(false)
            if (copied) return
        }
        logDebug("No bundled iptv-org FAST EPG in assets")
    }

    /**
     * AAPT may store [.xml.gz] decompressed as [.xml] in the APK (~80 MB).
     * Stream into the on-disk gzip cache to avoid OOM on low-memory STBs.
     */
    private fun copyBundledAsset(assetPath: String): Boolean {
        assetOpener(assetPath).use { input ->
            val header = ByteArray(2)
            val read = input.read(header)
            if (read <= 0) return false
            val isGzip = read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
            if (isGzip) {
                mergedFile.outputStream().use { output ->
                    output.write(header)
                    input.copyTo(output)
                }
            } else {
                GZIPOutputStream(mergedFile.outputStream()).use { gzip ->
                    gzip.write(header, 0, read)
                    input.copyTo(gzip)
                }
            }
        }
        if (mergedFile.length() <= 0L) return false
        metaFile.writeText(System.currentTimeMillis().toString())
        logInfo("Copied bundled iptv-org FAST EPG from $assetPath (${mergedFile.length()} bytes)")
        return true
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
        /** Primary bundled guide; .dat avoids AAPT decompressing .xml.gz in the APK. */
        const val BUNDLED_ASSET_DAT = "epg/iptv_org_fast_epg.dat"
        const val BUNDLED_ASSET_GZ = "epg/iptv_org_fast_epg.xml.gz"
        const val BUNDLED_ASSET_XML = "epg/iptv_org_fast_epg.xml"
        val BUNDLED_ASSET_PATHS = listOf(BUNDLED_ASSET_DAT, BUNDLED_ASSET_GZ, BUNDLED_ASSET_XML)

        private fun defaultClient(): OkHttpClient {
            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = SupplementConfig.HTTP_MAX_REQUESTS
                maxRequestsPerHost = SupplementConfig.HTTP_MAX_REQUESTS_PER_HOST
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(IptvOrgEpgConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(IptvOrgEpgConfig.DOWNLOAD_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }
}
