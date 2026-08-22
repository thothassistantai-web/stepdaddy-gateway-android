package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.XmltvParser
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads FAST provider XMLTV guides and maps display names → channel ids
 * for iptv-org supplements that ship with empty tvg-id in upstream M3U.
 *
 * Refresh is stale-while-revalidate friendly: [loadIndexFromDisk] / non-force [refresh]
 * reuse cache; force refresh downloads feeds in parallel.
 */
class FastEpgCatalog(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val dir = File(context.applicationContext.filesDir, "supplement/fast-epg").also { it.mkdirs() }
    private val metaFile = File(dir, "meta.txt")
    @Volatile
    private var nameToChannelId: Map<LookupKey, String> = emptyMap()

    data class LookupKey(val provider: String, val normName: String)

    fun lookupChannelId(channelName: String, providerTag: String?): String? {
        val provider = normalizeProvider(providerTag) ?: return null
        val norm = EpgChannelMapper.normalizeName(stripQuality(channelName))
        if (norm.isEmpty()) return null
        return nameToChannelId[LookupKey(provider, norm)]
    }

    /** Per-provider cached feeds — merged at EPG build time (avoids OOM). */
    fun cachedFeedFiles(): List<File> =
        FEED_URLS.keys.mapNotNull { existingCacheFile(it) }

    fun isStale(): Boolean {
        if (cachedFeedFiles().isEmpty()) return true
        val syncedAt = metaFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - syncedAt > CACHE_TTL_MS
    }

    fun hasUsableIndex(): Boolean = nameToChannelId.isNotEmpty() || cachedFeedFiles().isNotEmpty()

    /** Load name→id index from disk without network. */
    fun loadIndexFromDisk(): Boolean {
        val index = linkedMapOf<LookupKey, String>()
        var loaded = 0
        for (provider in FEED_URLS.keys) {
            val cache = existingCacheFile(provider) ?: continue
            indexChannels(cache, provider, index)
            loaded++
        }
        if (loaded == 0) return false
        nameToChannelId = index
        Log.i(TAG, "FAST EPG: loaded ${index.size} name mappings from $loaded cached feeds")
        return true
    }

    /**
     * @param force when false and cache is fresh with an in-memory index, no-op.
     *              when false and stale but disk exists, loads disk then caller may refresh in background.
     */
    fun refresh(force: Boolean = false) {
        if (!force && !isStale() && nameToChannelId.isNotEmpty()) return
        if (!force && !isStale() && nameToChannelId.isEmpty()) {
            if (loadIndexFromDisk()) return
        }
        if (!force && isStale() && nameToChannelId.isEmpty()) {
            loadIndexFromDisk()
        }
        runBlocking {
            refreshParallel(force = force || isStale() || cachedFeedFiles().isEmpty())
        }
    }

    suspend fun refreshAsync(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && !isStale() && nameToChannelId.isNotEmpty()) return@withContext
        if (!force && nameToChannelId.isEmpty()) {
            loadIndexFromDisk()
        }
        if (!force && !isStale() && nameToChannelId.isNotEmpty()) return@withContext
        refreshParallel(force = true)
    }

    private suspend fun refreshParallel(force: Boolean) {
        if (!force && !isStale() && nameToChannelId.isNotEmpty()) return
        val index = linkedMapOf<LookupKey, String>()
        val downloaded = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT_FEEDS)
        coroutineScope {
            FEED_URLS.map { (provider, url) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val cache = cacheFileFor(provider, url)
                        val ok = download(url, cache)
                        if (ok) {
                            downloaded.incrementAndGet()
                            synchronized(index) {
                                indexChannels(cache, provider, index)
                            }
                        } else {
                            existingCacheFile(provider)?.let { existing ->
                                synchronized(index) {
                                    indexChannels(existing, provider, index)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (downloaded.get() == 0 && index.isEmpty()) {
            Log.w(TAG, "FAST EPG refresh: no feeds downloaded")
            return
        }
        if (downloaded.get() > 0) {
            metaFile.writeText(System.currentTimeMillis().toString())
        }
        nameToChannelId = index.toMap()
        Log.i(TAG, "FAST EPG: ${index.size} name mappings from ${downloaded.get()} feeds (parallel)")
    }

    private fun indexChannels(
        file: File,
        provider: String,
        index: MutableMap<LookupKey, String>,
    ) {
        XmltvParser.iterAllBlocksFromFile(file, "channel", "channel").forEach { block ->
            val id = XmltvParser.blockAttrValue(block, "id") ?: return@forEach
            val display = Regex("<display-name[^>]*>([^<]+)</display-name>", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (display.isEmpty()) return@forEach
            val norm = EpgChannelMapper.normalizeName(stripQuality(display))
            if (norm.isNotEmpty()) {
                index.putIfAbsent(LookupKey(provider, norm), id)
            }
        }
    }

    private fun download(url: String, target: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
                            if (total > MAX_BYTES) error("fast_epg_too_large")
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

    private fun cacheFileFor(provider: String, url: String): File {
        val ext = if (url.endsWith(".gz", ignoreCase = true)) ".xml.gz" else ".xml"
        return File(dir, "${provider.lowercase()}$ext")
    }

    private fun existingCacheFile(provider: String): File? {
        val base = provider.lowercase()
        return listOf(File(dir, "$base.xml.gz"), File(dir, "$base.xml"))
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun stripQuality(name: String): String =
        name.replace(Regex("\\(\\s*\\d+p\\s*\\)", RegexOption.IGNORE_CASE), "").trim()

    private fun normalizeProvider(providerTag: String?): String? {
        val tag = providerTag?.trim().orEmpty()
        if (tag.isEmpty()) return null
        return when (tag.lowercase()) {
            "pluto" -> "Pluto"
            "samsung" -> "Samsung"
            "distro" -> "Distro"
            "plex" -> "Plex"
            "xumo" -> "Xumo"
            "roku" -> "Roku"
            "tubi" -> "Tubi"
            "local", "localnow" -> "LocalNow"
            "stirr" -> "STIRR"
            "firetv" -> "FireTV"
            "vizio" -> "Vizio"
            "tcl" -> "TCL"
            "30a" -> "30A"
            else -> tag.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    companion object {
        private const val TAG = "FastEpgCatalog"
        /** Roku gzip ~3.4MB; Tubi plain XML ~3MB — headroom for future growth. */
        private const val MAX_BYTES = 48 * 1024 * 1024L
        private const val CACHE_TTL_MS = 12 * 3600_000L
        private const val MAX_CONCURRENT_FEEDS = 4
        private const val USER_AGENT = "Mozilla/5.0 StepDaddy-Gateway/1.0"

        val FEED_URLS: Map<String, String> = mapOf(
            // i.mjh.nz (verified working Jun 2026)
            "Pluto" to "https://i.mjh.nz/PlutoTV/us.xml.gz",
            "Samsung" to "https://i.mjh.nz/SamsungTVPlus/us.xml.gz",
            "Plex" to "https://i.mjh.nz/Plex/us.xml.gz",
            // i.mjh.nz 404 as of Jun 2026 — alternate public sources
            "Roku" to "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/Roku/all.xml.gz",
            "Xumo" to "https://raw.githubusercontent.com/BuddyChewChew/xumo-playlist-generator/main/playlists/xumo_epg.xml.gz",
            "Tubi" to "https://raw.githubusercontent.com/BuddyChewChew/tubi-scraper/main/tubi_epg.xml",
            "LocalNow" to "https://raw.githubusercontent.com/BuddyChewChew/localnow-playlist-generator/main/epg.xml",
            // Distro: covered via epgshare DISTROTV1 in EpgConfig (mjh CDN 404).
            // Stirr: omitted — no stable public XMLTV feed (mjh CDN 404).
        )

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
