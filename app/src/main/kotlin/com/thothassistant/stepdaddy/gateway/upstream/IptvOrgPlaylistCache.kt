package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Per-playlist disk cache for iptv-org GitHub M3Us with ETag / Last-Modified support.
 */
class IptvOrgPlaylistCache(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    private val dir = File(context.applicationContext.filesDir, "supplement/iptv-org").also { it.mkdirs() }

    data class FetchResult(
        val filename: String,
        val body: String,
        val fromCache: Boolean,
        val httpStatus: Int,
    )

    fun cachedSizeBytes(filename: String): Long =
        bodyFile(filename).takeIf { it.isFile }?.length() ?: 0L

    fun fetch(filename: String): FetchResult? {
        val url = IptvOrgStreamsConfig.rawUrl(filename)
        val bodyFile = bodyFile(filename)
        val meta = readMeta(filename)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .apply {
                meta.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                meta.lastModified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
            }
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && bodyFile.isFile && bodyFile.length() > 0L -> {
                        FetchResult(
                            filename = filename,
                            body = bodyFile.readText(Charsets.UTF_8),
                            fromCache = true,
                            httpStatus = 304,
                        )
                    }
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes() ?: return null
                        if (bytes.isEmpty()) return null
                        if (bytes.size > IptvOrgStreamsConfig.MAX_BYTES_PER_PLAYLIST) {
                            Log.w(TAG, "iptv-org playlist too large: $filename (${bytes.size} bytes)")
                            return null
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        val sha = sha256Hex(bytes)
                        if (meta.sha256 == sha && bodyFile.isFile) {
                            // Body unchanged despite 200
                            return FetchResult(filename, bodyFile.readText(Charsets.UTF_8), fromCache = true, httpStatus = 200)
                        }
                        bodyFile.writeBytes(bytes)
                        writeMeta(
                            filename,
                            Meta(
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                sha256 = sha,
                                syncedAt = System.currentTimeMillis(),
                                sizeBytes = bytes.size.toLong(),
                            ),
                        )
                        FetchResult(filename, text, fromCache = false, httpStatus = response.code)
                    }
                    else -> {
                        Log.w(TAG, "iptv-org fetch failed ${response.code} $filename")
                        cachedBodyOrNull(filename)?.let {
                            FetchResult(filename, it, fromCache = true, httpStatus = response.code)
                        }
                    }
                }
            }
        }.getOrElse { exc ->
            Log.w(TAG, "iptv-org fetch error $filename", exc)
            cachedBodyOrNull(filename)?.let {
                FetchResult(filename, it, fromCache = true, httpStatus = 0)
            }
        }
    }

    private fun cachedBodyOrNull(filename: String): String? {
        val f = bodyFile(filename)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun bodyFile(filename: String): File = File(dir, filename)

    private fun metaFile(filename: String): File = File(dir, "$filename.meta")

    private data class Meta(
        val etag: String? = null,
        val lastModified: String? = null,
        val sha256: String? = null,
        val syncedAt: Long = 0L,
        val sizeBytes: Long = 0L,
    )

    private fun readMeta(filename: String): Meta {
        val f = metaFile(filename)
        if (!f.isFile) return Meta()
        val map = f.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        return Meta(
            etag = map["etag"],
            lastModified = map["lastModified"],
            sha256 = map["sha256"],
            syncedAt = map["syncedAt"]?.toLongOrNull() ?: 0L,
            sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun writeMeta(filename: String, meta: Meta) {
        metaFile(filename).writeText(
            buildString {
                meta.etag?.let { append("etag=").append(it).append('\n') }
                meta.lastModified?.let { append("lastModified=").append(it).append('\n') }
                meta.sha256?.let { append("sha256=").append(it).append('\n') }
                append("syncedAt=").append(meta.syncedAt).append('\n')
                append("sizeBytes=").append(meta.sizeBytes).append('\n')
            },
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "IptvOrgPlaylistCache"
    }
}
