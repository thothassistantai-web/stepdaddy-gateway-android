package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches Free-TV/IPTV country M3U playlists as live channel backups.
 * Skips YouTube/Twitch rows; publishes direct HTTP(S) stream URLs.
 */
class FreeTvIptvSource(
    httpClient: OkHttpClient,
) {
    /** Isolated + IPv4-preferring client so GitHub raw stalls do not wedge the shared pool. */
    private val fetchClient: OkHttpClient = httpClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 2
                maxRequestsPerHost = 1
            },
        )
        .dns(IptvOrgPlaylistCache.IPV4_PREFER_DNS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class FetchStats(
        val playlistsFetched: Int = 0,
        val playlistsFailed: Int = 0,
        val entriesParsed: Int = 0,
        val entriesSkippedNonHttp: Int = 0,
        val channelsAfterDedup: Int = 0,
        val daddyFallbacksAttached: Int = 0,
    )

    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: FetchStats,
        val daddyFallbacks: Map<String, List<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>> =
            emptyMap(),
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        enabledPlaylists: Set<String> = FreeTvIptvConfig.PLAYLIST_FILES.toSet(),
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val playlistFiles = FreeTvIptvConfig.PLAYLIST_FILES.filter { it in enabledPlaylists }
        if (playlistFiles.isEmpty()) {
            return@withContext FetchOutcome(emptyList(), FetchStats())
        }
        val semaphore = Semaphore(1)
        val parsed = coroutineScope {
            playlistFiles.map { filename ->
                async {
                    semaphore.withPermit { fetchPlaylist(filename) }
                }
            }.awaitAll()
        }

        var fetched = 0
        var failed = 0
        var skippedNonHttp = 0
        val allEntries = mutableListOf<M3uParser.Entry>()
        parsed.forEach { result ->
            if (result == null) {
                failed++
            } else {
                fetched++
                skippedNonHttp += result.skippedNonHttp
                allEntries += result.entries
            }
        }

        val filtered = SupplementDedup.filterNewChannels(
            entries = allEntries,
            daddyChannels = daddyChannels,
            maxChannels = FreeTvIptvConfig.MAX_CHANNELS_AFTER_DEDUP,
            importMode = importMode,
        ) { entry, _ ->
            toFreeTvChannel(entry, entry.sourcePlaylist.orEmpty())
        }

        FetchOutcome(
            channels = filtered.channels,
            stats = FetchStats(
                playlistsFetched = fetched,
                playlistsFailed = failed,
                entriesParsed = allEntries.size + skippedNonHttp,
                entriesSkippedNonHttp = skippedNonHttp,
                channelsAfterDedup = filtered.channels.size,
                daddyFallbacksAttached = filtered.daddyFallbacks.values.sumOf { it.size },
            ),
            daddyFallbacks = filtered.daddyFallbacks,
        )
    }

    private data class PlaylistResult(
        val filename: String,
        val entries: List<M3uParser.Entry>,
        val skippedNonHttp: Int,
    )

    private fun fetchPlaylist(filename: String): PlaylistResult? {
        val urls = FreeTvIptvConfig.candidateUrls(filename)
        var lastExc: Exception? = null
        for (url in urls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SupplementConfig.USER_AGENT)
                .get()
                .build()
            val result = runCatching {
                fetchClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Free-TV fetch failed ${response.code} $filename via $url")
                        return@use null
                    }
                    val body = response.body ?: return@use null
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) return@use null
                    if (bytes.size > FreeTvIptvConfig.MAX_BYTES_PER_PLAYLIST) {
                        Log.w(TAG, "Free-TV playlist too large: $filename (${bytes.size} bytes)")
                        return@use null
                    }
                    val text = bytes.toString(Charsets.UTF_8)
                    var skipped = 0
                    val entries = M3uParser.parse(text).mapNotNull { entry ->
                        if (!FreeTvIptvConfig.isPlayableHttpStream(entry.streamUrl)) {
                            skipped++
                            return@mapNotNull null
                        }
                        entry.copy(sourcePlaylist = filename)
                    }
                    PlaylistResult(filename, entries, skipped)
                }
            }.getOrElse { exc ->
                lastExc = exc as? Exception ?: Exception(exc)
                Log.d(TAG, "Free-TV fetch error $filename via $url (${exc.message})")
                null
            }
            if (result != null) return result
        }
        if (lastExc != null) {
            Log.w(TAG, "Free-TV all mirrors failed $filename (${lastExc.message})")
        } else {
            Log.w(TAG, "Free-TV all mirrors failed $filename")
        }
        return null
    }

    private fun toFreeTvChannel(entry: M3uParser.Entry, playlistFile: String): SupplementChannel {
        val name = entry.name.trim()
        val countryTag = FreeTvIptvConfig.countryTagFor(playlistFile)
        val resolution = GroupTitleResolver.resolve(
            channelName = name,
            tags = listOf(countryTag, "#freetv"),
            channelId = null,
        )
        val id = "${FreeTvIptvConfig.ID_PREFIX}${shortHash(name + "|" + entry.streamUrl)}"
        return SupplementChannel(
            id = id,
            name = name,
            tvgId = entry.tvgId?.trim()?.takeIf { it.isNotEmpty() },
            logo = entry.logo?.trim()?.takeIf { it.isNotEmpty() },
            groupTitle = resolution.groupTitle,
            streamUrl = entry.streamUrl.trim(),
            tags = listOf(countryTag, "#freetv", "#live"),
            providerTag = FreeTvIptvConfig.PROVIDER_TAG,
            referer = FreeTvIptvConfig.REFERER,
            origin = FreeTvIptvConfig.ORIGIN,
        )
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FreeTvIptvSource"
    }
}
