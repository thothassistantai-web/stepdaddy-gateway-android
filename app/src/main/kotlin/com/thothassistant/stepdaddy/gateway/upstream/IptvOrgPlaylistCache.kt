package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Per-playlist disk cache for iptv-org GitHub M3Us with ETag / Last-Modified support.
 *
 * Phone LTE (esp. MVNOs) often ICMP-pings GitHub/jsDelivr but stalls on HTTPS reads.
 * Soft TTL + CDN circuit breaker prefer disk over burning minutes on mirror timeouts.
 */
class IptvOrgPlaylistCache(
    context: Context,
    httpClient: OkHttpClient,
) {
    /** Isolated client so GitHub/CDN fetches are not queued behind AdultSwim/TMDB stampede. */
    private val fetchClient: OkHttpClient = httpClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 2
                maxRequestsPerHost = 1
            },
        )
        .dns(IPV4_PREFER_DNS)
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
        val bodyFile = bodyFile(filename)
        val meta = readMeta(filename)
        val cachedBody = cachedBodyOrNull(filename)

        // Warm disk within soft TTL — avoid stampeding GitHub/CDN on every sync.
        if (cachedBody != null && meta.syncedAt > 0L) {
            val ageMs = System.currentTimeMillis() - meta.syncedAt
            if (ageMs in 0 until CACHE_SOFT_TTL_MS) {
                return FetchResult(filename, cachedBody, fromCache = true, httpStatus = 0)
            }
        }

        // Circuit open: skip mirror walk and serve any disk we have.
        if (isCircuitOpen() && cachedBody != null) {
            logDegradedOnce("iptv-org CDN circuit open — serving disk cache for $filename")
            return FetchResult(filename, cachedBody, fromCache = true, httpStatus = 0)
        }

        val urls = IptvOrgStreamsConfig.candidateUrls(filename)
        var lastExc: Exception? = null
        var anyTransportFailure = false
        for ((index, url) in urls.withIndex()) {
            // After first transport failure on a cold sync, try one retry with backoff then next mirror.
            repeat(ATTEMPTS_PER_URL) { attempt ->
                if (attempt > 0) {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt)
                }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", SupplementConfig.USER_AGENT)
                    .apply {
                        // Conditional headers only for GitHub raw (CDN ETags differ).
                        if (url.startsWith(IptvOrgStreamsConfig.RAW_BASE_URL)) {
                            meta.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                            meta.lastModified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
                        }
                    }
                    .get()
                    .build()
                val result = runCatching {
                    fetchClient.newCall(request).execute().use { response ->
                        when {
                            response.code == 304 && bodyFile.isFile && bodyFile.length() > 0L -> {
                                noteSuccess()
                                FetchResult(
                                    filename = filename,
                                    body = bodyFile.readText(Charsets.UTF_8),
                                    fromCache = true,
                                    httpStatus = 304,
                                )
                            }
                            response.isSuccessful -> {
                                val bytes = response.body?.bytes() ?: return@use null
                                if (bytes.isEmpty()) return@use null
                                if (bytes.size > IptvOrgStreamsConfig.MAX_BYTES_PER_PLAYLIST) {
                                    Log.w(TAG, "iptv-org playlist too large: $filename (${bytes.size} bytes)")
                                    return@use null
                                }
                                val text = bytes.toString(Charsets.UTF_8)
                                val sha = sha256Hex(bytes)
                                noteSuccess()
                                if (meta.sha256 == sha && bodyFile.isFile) {
                                    FetchResult(filename, bodyFile.readText(Charsets.UTF_8), fromCache = true, httpStatus = 200)
                                } else {
                                    bodyFile.writeBytes(bytes)
                                    writeMeta(
                                        filename,
                                        Meta(
                                            etag = if (url.startsWith(IptvOrgStreamsConfig.RAW_BASE_URL)) {
                                                response.header("ETag")
                                            } else {
                                                meta.etag
                                            },
                                            lastModified = if (url.startsWith(IptvOrgStreamsConfig.RAW_BASE_URL)) {
                                                response.header("Last-Modified")
                                            } else {
                                                meta.lastModified
                                            },
                                            sha256 = sha,
                                            syncedAt = System.currentTimeMillis(),
                                            sizeBytes = bytes.size.toLong(),
                                        ),
                                    )
                                    if (index > 0) {
                                        Log.i(TAG, "iptv-org fetched via CDN[$index]: $filename")
                                    }
                                    FetchResult(filename, text, fromCache = false, httpStatus = response.code)
                                }
                            }
                            else -> {
                                logFetchWarn("iptv-org fetch failed ${response.code} $filename via $url")
                                null
                            }
                        }
                    }
                }.getOrElse { exc ->
                    lastExc = exc as? Exception ?: Exception(exc)
                    anyTransportFailure = true
                    logFetchWarn("iptv-org fetch error $filename via $url (${exc.message})")
                    null
                }
                if (result != null) return result
            }
        }

        if (anyTransportFailure) {
            noteFailure()
        }
        val errMsg = lastExc?.message
        if (lastExc != null && cachedBody == null) {
            logFetchWarn("iptv-org all mirrors failed $filename: $errMsg")
        } else if (lastExc != null && cachedBody != null) {
            logDegradedOnce("iptv-org CDN unreachable — serving disk cache ($filename)")
        }

        return cachedBody?.let {
            FetchResult(filename, it, fromCache = true, httpStatus = 0)
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

        /** Prefer disk within this window before spending timeouts on mirrors. */
        private const val CACHE_SOFT_TTL_MS = 12 * 3600_000L

        /** GitHub/jsDelivr need headroom on MVNO LTE (ICMP works; HTTPS stalls). */
        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 30L
        private const val CALL_TIMEOUT_SEC = 40L
        private const val ATTEMPTS_PER_URL = 2
        private const val RETRY_BACKOFF_MS = 750L

        private const val CIRCUIT_FAILURES_TO_OPEN = 4
        private const val CIRCUIT_OPEN_MS = 10 * 60_000L
        private const val WARN_EVERY_MS = 30_000L

        private val consecutiveFailures = AtomicInteger(0)
        private val circuitOpenUntilMs = AtomicLong(0L)
        private val lastWarnMs = AtomicLong(0L)
        private val lastDegradedMs = AtomicLong(0L)

        /** Prefer IPv4 — some LTE stacks hang on IPv6 AAAA for GitHub/CDN hosts. */
        val IPV4_PREFER_DNS: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).sortedBy { addr -> if (addr is Inet4Address) 0 else 1 }
        }

        fun isCircuitOpen(now: Long = System.currentTimeMillis()): Boolean =
            now < circuitOpenUntilMs.get()

        private fun noteSuccess() {
            consecutiveFailures.set(0)
            circuitOpenUntilMs.set(0L)
        }

        private fun noteFailure() {
            val n = consecutiveFailures.incrementAndGet()
            if (n >= CIRCUIT_FAILURES_TO_OPEN) {
                circuitOpenUntilMs.set(System.currentTimeMillis() + CIRCUIT_OPEN_MS)
                logDegradedOnce(
                    "iptv-org CDN circuit open for ${CIRCUIT_OPEN_MS / 1000}s after $n failures",
                )
            }
        }

        private fun logFetchWarn(message: String) {
            val now = System.currentTimeMillis()
            val prev = lastWarnMs.get()
            if (now - prev >= WARN_EVERY_MS && lastWarnMs.compareAndSet(prev, now)) {
                Log.w(TAG, message)
            } else {
                Log.d(TAG, message)
            }
        }

        private fun logDegradedOnce(message: String) {
            val now = System.currentTimeMillis()
            val prev = lastDegradedMs.get()
            if (now - prev >= WARN_EVERY_MS && lastDegradedMs.compareAndSet(prev, now)) {
                Log.w(TAG, message)
            }
        }
    }
}
