package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Fetches programme grids from tvtv.us for playlist [tvg-id]s listed in [TvtvUsEpgConfig.BRIDGE_ASSET].
 * Output channel ids match playlist ids (e.g. LifetimeNetwork.us) while site_id selects the feed row.
 */
class TvtvUsEpgFetcher(
    context: Context,
    private val store: EpgStore,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val bridgeByPlaylistId: Map<String, BridgeEntry> = loadBundled(context)

    init {
        Log.i(TAG, "tvtv.us EPG bridge: ${bridgeByPlaylistId.size} playlist ids")
    }

    fun bridgeSize(): Int = bridgeByPlaylistId.size

    fun bridgeEntry(playlistTvgId: String): BridgeEntry? = bridgeByPlaylistId[playlistTvgId.trim()]

    fun mergeGapFill(
        writer: BufferedWriter,
        playlistIds: List<String>,
        channelNamesByTvgId: Map<String, String>,
        writtenChannelIds: MutableSet<String>,
        idsWithProgrammes: MutableSet<String>,
        windowStart: Instant,
        windowEnd: Instant,
        channelCountRef: (Int) -> Unit,
        programmeCountRef: (Int) -> Unit,
        getChannelCount: () -> Int,
        getProgrammeCount: () -> Int,
    ) {
        val wanted = playlistIds.mapNotNull { playlistId ->
            val entry = bridgeByPlaylistId[playlistId] ?: return@mapNotNull null
            playlistId to entry
        }.take(TvtvUsEpgConfig.MAX_CHANNELS_PER_BUILD)
        if (wanted.isEmpty()) return

        var channelCount = getChannelCount()
        var programmeCount = getProgrammeCount()

        wanted.forEachIndexed { index, (playlistId, entry) ->
            if (index > 0) {
                Thread.sleep(TvtvUsEpgConfig.GRID_REQUEST_DELAY_MS)
            }
            val programmes = runCatching {
                fetchProgrammesChunked(windowStart, windowEnd, entry.siteId)
            }.getOrElse { exc ->
                Log.w(TAG, "tvtv.us grid failed for $playlistId (${entry.siteId}): ${exc.message}")
                emptyList()
            }
            if (programmes.isEmpty()) return@forEachIndexed

            val channelId = playlistId
            if (writtenChannelIds.add(channelId)) {
                val displayName = channelNamesByTvgId[playlistId] ?: channelId
                writer.write("\n<channel id=\"${escapeXml(channelId)}\">")
                writer.write("<display-name>${escapeXml(displayName)}</display-name>")
                writer.write("</channel>")
                channelCount++
            }

            programmes.forEach { programme ->
                writer.write(
                    "\n<programme start=\"${programme.startXml}\" stop=\"${programme.stopXml}\" " +
                        "channel=\"${escapeXml(channelId)}\">",
                )
                writer.write("<title>${escapeXml(programme.title)}</title>")
                programme.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    writer.write("<sub-title>${escapeXml(subtitle)}</sub-title>")
                }
                writer.write("</programme>")
                programmeCount++
            }
            idsWithProgrammes += playlistId
        }

        channelCountRef(channelCount)
        programmeCountRef(programmeCount)
    }


    /** tvtv.us grid API rejects windows longer than ~24h (HTTP 400). */
    private fun fetchProgrammesChunked(
        windowStart: Instant,
        windowEnd: Instant,
        siteId: String,
    ): List<ParsedProgramme> {
        val merged = linkedMapOf<String, ParsedProgramme>()
        var chunkStart = windowStart
        while (chunkStart.isBefore(windowEnd)) {
            if (ChronoUnit.HOURS.between(chunkStart, windowEnd) < TvtvUsEpgConfig.MIN_GRID_WINDOW_HOURS) {
                break
            }
            val chunkEnd = minOf(
                chunkStart.plus(TvtvUsEpgConfig.MAX_GRID_WINDOW_HOURS, ChronoUnit.HOURS),
                windowEnd,
            )
            if (!chunkEnd.isAfter(chunkStart)) break
            val url = TvtvUsEpgConfig.gridUrl(
                formatApiInstant(chunkStart),
                formatApiInstant(chunkEnd),
                siteId,
            )
            val body = downloadGridJson(url)
            parseGridJson(body, windowStart, windowEnd).forEach { programme ->
                val key = "${programme.startXml}|${programme.title}"
                merged[key] = programme
            }
            chunkStart = chunkEnd
            if (chunkStart.isBefore(windowEnd)) {
                Thread.sleep(TvtvUsEpgConfig.GRID_REQUEST_DELAY_MS)
            }
        }
        return merged.values.sortedBy { it.startXml }
    }

    private fun downloadGridJson(url: String): String {
        val cache = store.feedCacheFile(url)
        if (isGridFresh(cache)) {
            return cache.readText(Charsets.UTF_8)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TvtvUsEpgConfig.USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()
        val tmp = java.io.File(cache.parentFile, "${cache.name}.part")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("tvtv_grid_download_failed:${response.code}")
            val body = response.body ?: error("tvtv_grid_empty")
            val sink = tmp.outputStream()
            val max = TvtvUsEpgConfig.MAX_GRID_BYTES.toLong()
            var total = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > max) error("tvtv_grid_exceeded_max_bytes")
                    sink.write(buffer, 0, read)
                }
            }
            sink.close()
        }
        if (!tmp.renameTo(cache)) {
            cache.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        return cache.readText(Charsets.UTF_8)
    }

    private fun isGridFresh(file: java.io.File): Boolean {
        if (!file.exists()) return false
        return System.currentTimeMillis() - file.lastModified() < TvtvUsEpgConfig.GRID_CACHE_TTL_MS
    }

    private fun loadBundled(context: Context): Map<String, BridgeEntry> =
        runCatching {
            val text = context.assets.open(TvtvUsEpgConfig.BRIDGE_ASSET).bufferedReader().use { it.readText() }
            parseBridgeJson(text)
        }.getOrElse { exc ->
            Log.w(TAG, "tvtv.us bridge load failed: ${exc.message}")
            emptyMap()
        }

    data class BridgeEntry(
        val siteId: String,
        val xmltvId: String,
    )

    companion object {
        @Serializable
        private data class BridgeAsset(
            val bridge: Map<String, BridgeEntryDto> = emptyMap(),
            val mappings: Map<String, String> = emptyMap(),
            val site_by_xmltv_id: Map<String, String> = emptyMap(),
            val generated_at: String? = null,
            val bridge_count: Int = 0,
            val version: Int = 0,
        )

        @Serializable
        private data class BridgeEntryDto(
            val site_id: String = "",
            val xmltv_id: String = "",
        )

        @Serializable
        private data class GridItem(
            val title: String = "",
            val subtitle: String? = null,
            val startTime: String = "",
            val duration: Int = 0,
        )

        private data class ParsedProgramme(
            val title: String,
            val subtitle: String?,
            val startXml: String,
            val stopXml: String,
        )

        private const val TAG = "TvtvUsEpgFetcher"
        private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

        fun formatApiInstant(instant: Instant): String =
            DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

        private fun parseBridge(raw: Map<String, BridgeEntryDto>): Map<String, BridgeEntry> =
            raw.mapNotNull { (playlistId, entry) ->
                val key = playlistId.trim()
                val siteId = entry.site_id.trim()
                val xmltvId = entry.xmltv_id.trim().ifEmpty { key }
                if (key.isEmpty() || siteId.isEmpty()) null else key to BridgeEntry(siteId, xmltvId)
            }.toMap()

        fun parseBridgeJson(text: String): Map<String, BridgeEntry> {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.decodeFromString<BridgeAsset>(text)
            if (root.bridge.isNotEmpty()) {
                return parseBridge(root.bridge)
            }
            return parseLegacyMappings(root.mappings, root.site_by_xmltv_id)
        }

        private fun parseLegacyMappings(
            mappings: Map<String, String>,
            siteLookup: Map<String, String>,
        ): Map<String, BridgeEntry> =
            mappings.mapNotNull { (playlistId, feedId) ->
                val key = playlistId.trim()
                val feed = feedId.trim()
                if (key.isEmpty() || feed.isEmpty()) return@mapNotNull null
                val siteId = siteLookup[feed]?.trim().orEmpty()
                if (siteId.isEmpty()) return@mapNotNull null
                key to BridgeEntry(siteId = siteId, xmltvId = key)
            }.toMap()

        private fun parseGridJson(
            body: String,
            windowStart: Instant,
            windowEnd: Instant,
        ): List<ParsedProgramme> {
            val json = Json { ignoreUnknownKeys = true }
            val rows = runCatching {
                json.decodeFromString<List<List<GridItem>>>(body).firstOrNull().orEmpty()
            }.getOrElse { emptyList() }
            return rows.mapNotNull { item ->
                val start = runCatching { Instant.parse(item.startTime) }.getOrNull() ?: return@mapNotNull null
                val stop = start.plus(item.duration.toLong(), ChronoUnit.MINUTES)
                if (!stop.isAfter(windowStart) || !start.isBefore(windowEnd)) return@mapNotNull null
                val title = item.title.trim()
                if (title.isEmpty()) return@mapNotNull null
                ParsedProgramme(
                    title = title,
                    subtitle = item.subtitle?.trim()?.takeIf { it.isNotEmpty() },
                    startXml = XMLTV_TIME.format(start),
                    stopXml = XMLTV_TIME.format(stop),
                )
            }
        }

        private fun escapeXml(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(TvtvUsEpgConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(TvtvUsEpgConfig.DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                .build()
    }
}
