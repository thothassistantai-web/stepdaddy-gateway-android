package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads WhatsOnFreeTV JSON EPG (GitHub) and fills programme gaps by channel name match.
 * Used for FAST / free-streaming channels that lack epgshare or iptv-org guide rows.
 */
class WhatsOnFreeTvEpgCatalog(
    context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val dir = File(context.applicationContext.filesDir, "epg/woftv").also { it.mkdirs() }
    private val metaFile = File(dir, "meta.txt")

    @Volatile
    private var programmesByChannelKey: Map<String, List<Entry>> = emptyMap()

    data class Entry(
        val channel: String,
        val title: String,
        val description: String,
        val start: Instant,
        val end: Instant,
        val platform: String,
    )

    data class MergeResult(
        val programmesWritten: Int = 0,
        val channelsFilled: Int = 0,
    )

    fun channelKeyCount(): Int = programmesByChannelKey.size

    fun indexReady(): Boolean = programmesByChannelKey.isNotEmpty()

    fun cacheStale(): Boolean = isStale()

    @Serializable
    private data class EpgFile(
        val updated: String? = null,
        val date: String? = null,
        val count: Int = 0,
        val programs: List<ProgramJson> = emptyList(),
    )

    @Serializable
    private data class ProgramJson(
        val channel: String,
        val title: String,
        val description: String = "",
        val start: String,
        val end: String,
        val platform: String = "",
    )

    fun hasUsableIndex(): Boolean = programmesByChannelKey.isNotEmpty()

    fun isStale(): Boolean {
        if (programmesByChannelKey.isEmpty() && cachedJsonFiles().isEmpty()) return true
        val syncedAt = metaFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - syncedAt > WhatsOnFreeTvEpgConfig.CACHE_TTL_MS
    }

    fun loadIndexFromDisk(): Boolean {
        val merged = linkedMapOf<String, MutableList<Entry>>()
        var loaded = 0
        for ((country, filename) in WhatsOnFreeTvEpgConfig.EPG_FILES) {
            val file = cacheFile(country, filename)
            if (!file.isFile) continue
            indexFile(file, merged)
            loaded++
        }
        if (loaded == 0) return false
        programmesByChannelKey = merged.mapValues { (_, list) -> list.toList() }
        Log.i(TAG, "WOFTV EPG: loaded ${programmesByChannelKey.size} channel keys from $loaded files")
        return true
    }

    suspend fun refreshAsync(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && !isStale() && programmesByChannelKey.isNotEmpty()) return@withContext
        if (!force && programmesByChannelKey.isEmpty()) {
            loadIndexFromDisk()
        }
        if (!force && !isStale() && programmesByChannelKey.isNotEmpty()) return@withContext
        refreshParallel()
    }

    /**
     * Merge real programmes for [gapTvgIds] using [channelNamesByTvgId] name lookup.
     * Returns programme rows written (not placeholder).
     */
    fun mergeGaps(
        writer: BufferedWriter,
        gapTvgIds: Set<String>,
        channelNamesByTvgId: Map<String, String>,
        windowStart: Instant,
        windowEnd: Instant,
        writtenChannelIds: MutableSet<String>,
        idsWithProgrammes: MutableSet<String>,
        forceRetryIds: Set<String> = emptySet(),
    ): MergeResult {
        if (gapTvgIds.isEmpty() || programmesByChannelKey.isEmpty()) return MergeResult()
        var written = 0
        var channelsFilled = 0
        var skippedNoName = 0
        for (tvgId in gapTvgIds) {
            if (tvgId in idsWithProgrammes && tvgId !in forceRetryIds) continue
            val displayName = channelNamesByTvgId[tvgId]?.trim().orEmpty()
            if (displayName.isEmpty()) {
                skippedNoName++
                continue
            }
            val candidates = lookupProgrammes(displayName)
            if (candidates.isEmpty()) continue
            val inWindow = candidates.filter { prog ->
                prog.end.isAfter(windowStart) && prog.start.isBefore(windowEnd) &&
                    !isPlaceholderTitle(prog.title)
            }
            if (inWindow.isEmpty()) continue
            if (writtenChannelIds.add(tvgId)) {
                writer.write("\n<channel id=\"${escapeXml(tvgId)}\">")
                writer.write("<display-name>${escapeXml(displayName)}</display-name>")
                writer.write("</channel>")
            }
            for (prog in inWindow) {
                writer.write(
                    "\n<programme start=\"${formatXmltv(prog.start)}\" " +
                        "stop=\"${formatXmltv(prog.end)}\" channel=\"${escapeXml(tvgId)}\">",
                )
                writer.write("<title>${escapeXml(prog.title)}</title>")
                if (prog.description.isNotBlank()) {
                    writer.write("<desc>${escapeXml(prog.description)}</desc>")
                }
                writer.write("</programme>")
                written++
            }
            idsWithProgrammes += tvgId
            channelsFilled++
        }
        if (skippedNoName > 0) {
            Log.d(TAG, "WOFTV merge skipped $skippedNoName gap ids (no display name)")
        }
        return MergeResult(programmesWritten = written, channelsFilled = channelsFilled)
    }

    @VisibleForTesting
    internal fun loadTestIndex(index: Map<String, List<Entry>>) {
        programmesByChannelKey = index
    }

    @VisibleForTesting
    internal fun lookupProgrammesForTest(displayName: String): List<Entry> = lookupProgrammes(displayName)

    private fun lookupProgrammes(displayName: String): List<Entry> {
        for (variant in lookupNameVariants(displayName)) {
            val norm = EpgChannelMapper.normalizeName(variant)
            if (norm.isEmpty()) continue
            WhatsOnFreeTvEpgConfig.NAME_ALIASES[norm]?.let { alias ->
                programmesByChannelKey[alias]?.let { return it }
            }
            programmesByChannelKey[norm]?.let { return it }
            fuzzyMatchKey(norm)?.let { key ->
                programmesByChannelKey[key]?.let { return it }
            }
            val scored = bestScoredKey(norm, variant)
            if (scored != null) {
                programmesByChannelKey[scored]?.let { return it }
            }
        }
        return emptyList()
    }

    private fun lookupNameVariants(displayName: String): List<String> {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return emptyList()
        val variants = linkedSetOf(trimmed)
        val noPrefix = trimmed.replace(
            Regex("""^(US|UK|CA|INT|ES|DE|FR|IT|AU|NZ|MX|BR|CZ):\s*""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        if (noPrefix.isNotEmpty()) variants += noPrefix
        val noQuality = noPrefix.replace(
            Regex("""\s+(SD|HD|FHD|4K|UHD)\s*$""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        if (noQuality.isNotEmpty()) variants += noQuality
        return variants.toList()
    }

    private fun fuzzyMatchKey(norm: String): String? =
        fuzzyMatchKeyStatic(norm, programmesByChannelKey.keys)

    private fun bestScoredKey(norm: String, rawDisplay: String): String? {
        if (programmesByChannelKey.isEmpty()) return null
        val queryTokens = norm.split(' ').filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty()) return null
        var bestKey: String? = null
        var bestScore = WhatsOnFreeTvEpgConfig.MIN_LOOKUP_SCORE
        for ((key, entries) in programmesByChannelKey) {
            if (key.length < 3) continue
            val keyTokens = key.split(' ').toSet()
            val union = queryTokens.union(keyTokens)
            if (union.isEmpty()) continue
            val jaccard = queryTokens.intersect(keyTokens).size.toFloat() / union.size
            if (jaccard < 0.25f) continue
            val seq = sequenceRatio(norm, key)
            val rawBonus = entries.maxOfOrNull { entry ->
                sequenceRatio(rawDisplay.lowercase(Locale.US), entry.channel.lowercase(Locale.US))
            } ?: 0f
            val score = 0.45f * jaccard + 0.35f * seq + 0.20f * rawBonus
            if (score >= bestScore) {
                bestScore = score
                bestKey = key
            }
        }
        return bestKey
    }

    private fun sequenceRatio(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshteinDistance(a, b)
        return 1f - distance.toFloat() / maxOf(a.length, b.length)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            prev.indices.forEach { idx -> prev[idx] = curr[idx] }
        }
        return prev[b.length]
    }

    private suspend fun refreshParallel() {
        val merged = linkedMapOf<String, MutableList<Entry>>()
        var downloaded = 0
        coroutineScope {
            WhatsOnFreeTvEpgConfig.EPG_FILES.map { (country, filename) ->
                async(Dispatchers.IO) {
                    val target = cacheFile(country, filename)
                    val ok = download(filename, target)
                    if (ok) {
                        synchronized(merged) {
                            indexFile(target, merged)
                        }
                        true
                    } else {
                        val existing = cacheFile(country, filename)
                        if (existing.isFile) {
                            synchronized(merged) {
                                indexFile(existing, merged)
                            }
                        }
                        false
                    }
                }
            }.awaitAll().forEach { if (it) downloaded++ }
        }
        if (merged.isEmpty()) {
            Log.w(TAG, "WOFTV EPG refresh: no data loaded")
            return
        }
        if (downloaded > 0) {
            metaFile.writeText(System.currentTimeMillis().toString())
        }
        programmesByChannelKey = merged.mapValues { (_, list) -> list.toList() }
        Log.i(
            TAG,
            "WOFTV EPG refresh: ${programmesByChannelKey.size} channel keys " +
                "from $downloaded fresh downloads",
        )
    }

    private fun indexFile(file: File, merged: MutableMap<String, MutableList<Entry>>) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { json.decodeFromString<EpgFile>(text) }.getOrElse {
            Log.w(TAG, "WOFTV parse failed ${file.name}: ${it.message}")
            return
        }
        for (row in parsed.programs) {
            if (isPlaceholderTitle(row.title)) continue
            val start = parseInstant(row.start) ?: continue
            val end = parseInstant(row.end) ?: continue
            if (!end.isAfter(start)) continue
            val key = EpgChannelMapper.normalizeName(row.channel)
            if (key.isEmpty()) continue
            merged.getOrPut(key) { mutableListOf() } +=
                Entry(
                    channel = row.channel.trim(),
                    title = row.title.trim(),
                    description = row.description.trim(),
                    start = start,
                    end = end,
                    platform = row.platform.trim(),
                )
        }
    }

    private fun download(filename: String, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.part")
        for (url in WhatsOnFreeTvEpgConfig.cdnUrls(filename)) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WhatsOnFreeTvEpgConfig.USER_AGENT)
                .get()
                .build()
            val ok = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    var total = 0L
                    tmp.outputStream().use { sink ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > WhatsOnFreeTvEpgConfig.MAX_BYTES) error("woftv_epg_too_large")
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    true
                }
            }.getOrElse {
                Log.d(TAG, "WOFTV download failed $filename via $url (${it.message})")
                false
            }
            if (ok) {
                if (!tmp.renameTo(target)) {
                    target.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
                return true
            }
        }
        return false
    }

    private fun cachedJsonFiles(): List<File> =
        WhatsOnFreeTvEpgConfig.EPG_FILES.map { (country, filename) -> cacheFile(country, filename) }
            .filter { it.isFile }

    private fun cacheFile(country: String, filename: String): File =
        File(dir, "${country.lowercase(Locale.US)}_${filename}")

    private fun parseInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw.trim()) }.getOrNull()
            ?: runCatching {
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw.trim(), Instant::from)
            }.getOrNull()

    private fun isPlaceholderTitle(title: String): Boolean =
        title.trim().lowercase(Locale.US).contains(WhatsOnFreeTvEpgConfig.PLACEHOLDER_TITLE)

    private fun formatXmltv(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        private const val TAG = "WhatsOnFreeTvEpg"
        private val json = Json { ignoreUnknownKeys = true }

        /** Prefer the longest substring key so "pluto comedy movies" beats "pluto comedy". */
        @VisibleForTesting
        internal fun fuzzyMatchKeyStatic(norm: String, keys: Iterable<String>): String? {
            var bestKey: String? = null
            var bestLen = 0
            for (key in keys) {
                if (key.length < 4) continue
                if (!(norm.contains(key) || key.contains(norm))) continue
                if (key.length > bestLen) {
                    bestLen = key.length
                    bestKey = key
                }
            }
            return bestKey
        }

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(WhatsOnFreeTvEpgConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(WhatsOnFreeTvEpgConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(WhatsOnFreeTvEpgConfig.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
    }
}
