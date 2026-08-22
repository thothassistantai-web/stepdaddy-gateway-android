package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.FastChannelTvgIdResolver
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Fetches UK/US iptv-org stream playlists from GitHub on each supplement sync.
 * Uses per-playlist disk cache + ETag; publishes progressive waves before all 39 finish.
 */
class IptvOrgStreamsSource(
    context: Context,
    httpClient: OkHttpClient,
    private val channelResolver: IptvOrgChannelResolver = IptvOrgChannelResolver(context),
    private val fastEpgCatalog: FastEpgCatalog? = null,
    private val fastChannelTvgIdResolver: FastChannelTvgIdResolver? = null,
    private val nameIndex: com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex? = null,
    private val lowRamDevice: Boolean = false,
) {
    private val playlistCache = IptvOrgPlaylistCache(context, httpClient)

    data class FetchStats(
        val playlistsFetched: Int = 0,
        val playlistsFailed: Int = 0,
        val playlistsFromCache: Int = 0,
        val entriesParsed: Int = 0,
        val channelsAfterDedup: Int = 0,
        val daddyFallbacksAttached: Int = 0,
        val playlistsTotal: Int = 0,
    )

    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: FetchStats,
        val daddyFallbacks: Map<String, List<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>> = emptyMap(),
    )

    /**
     * @param onProgress invoked after each wave with a provisional outcome (capped/deduped).
     */
    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        enabledPlaylists: Set<String> = IptvOrgStreamsConfig.PLAYLIST_FILES.toSet(),
        onProgress: (suspend (FetchOutcome) -> Unit)? = null,
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val playlistFiles = orderPlaylists(
                IptvOrgStreamsConfig.PLAYLIST_FILES.filter { it in enabledPlaylists },
            )
            if (playlistFiles.isEmpty()) {
                return@withContext FetchOutcome(emptyList(), FetchStats())
            }
            val concurrency = if (lowRamDevice) {
                IptvOrgStreamsConfig.MAX_CONCURRENT_FETCH_FIRE
            } else {
                IptvOrgStreamsConfig.MAX_CONCURRENT_FETCH
            }
            val semaphore = Semaphore(concurrency)
            val allEntries = mutableListOf<M3uParser.Entry>()
            val entriesLock = Mutex()
            var fetched = 0
            var failed = 0
            var fromCache = 0
            var completed = 0

            coroutineScope {
                playlistFiles.map { filename ->
                    async {
                        val result = semaphore.withPermit {
                            fetchPlaylist(filename)
                        }
                        var progressSnapshot: FetchOutcome? = null
                        entriesLock.withLock {
                            if (result == null) {
                                failed++
                            } else {
                                fetched++
                                if (result.fromCache) fromCache++
                                allEntries += result.entries
                            }
                            completed++
                            val shouldPublish =
                                onProgress != null &&
                                    (completed % WAVE_SIZE == 0 || completed == playlistFiles.size)
                            if (shouldPublish) {
                                val snapshot = allEntries.toList()
                                val filtered = SupplementDedup.filterNewChannels(
                                    entries = snapshot,
                                    daddyChannels = daddyChannels,
                                    maxChannels = IptvOrgStreamsConfig.MAX_CHANNELS_AFTER_DEDUP,
                                    importMode = importMode,
                                ) { entry, _ ->
                                    toIptvOrgChannel(entry, entry.sourcePlaylist.orEmpty())
                                }
                                progressSnapshot = FetchOutcome(
                                    channels = filtered.channels,
                                    stats = FetchStats(
                                        playlistsFetched = fetched,
                                        playlistsFailed = failed,
                                        playlistsFromCache = fromCache,
                                        entriesParsed = snapshot.size,
                                        channelsAfterDedup = filtered.channels.size,
                                        daddyFallbacksAttached = filtered.daddyFallbacks.values.sumOf { it.size },
                                        playlistsTotal = playlistFiles.size,
                                    ),
                                    daddyFallbacks = filtered.daddyFallbacks,
                                )
                            }
                        }
                        progressSnapshot?.let { onProgress?.invoke(it) }
                    }
                }.awaitAll()
            }

            val filtered = SupplementDedup.filterNewChannels(
                entries = allEntries,
                daddyChannels = daddyChannels,
                maxChannels = IptvOrgStreamsConfig.MAX_CHANNELS_AFTER_DEDUP,
                importMode = importMode,
            ) { entry, _ ->
                toIptvOrgChannel(entry, entry.sourcePlaylist.orEmpty())
            }

            FetchOutcome(
                channels = filtered.channels,
                stats = FetchStats(
                    playlistsFetched = fetched,
                    playlistsFailed = failed,
                    playlistsFromCache = fromCache,
                    entriesParsed = allEntries.size,
                    channelsAfterDedup = filtered.channels.size,
                    daddyFallbacksAttached = filtered.daddyFallbacks.values.sumOf { it.size },
                    playlistsTotal = playlistFiles.size,
                ),
                daddyFallbacks = filtered.daddyFallbacks,
            )
        }

    /**
     * Rebuild iptv-org channels from on-disk playlist bodies only (no network).
     * Used when the sync slot times out or GitHub/CDN is unreachable on LTE.
     */
    suspend fun fetchChannelsFromDiskCache(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        enabledPlaylists: Set<String> = IptvOrgStreamsConfig.PLAYLIST_FILES.toSet(),
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val playlistFiles = IptvOrgStreamsConfig.PLAYLIST_FILES.filter { it in enabledPlaylists }
            if (playlistFiles.isEmpty()) {
                return@withContext FetchOutcome(emptyList(), FetchStats())
            }
            val allEntries = mutableListOf<M3uParser.Entry>()
            var fromCache = 0
            var failed = 0
            for (filename in playlistFiles) {
                val cached = playlistCache.loadDiskOnly(filename)
                if (cached == null) {
                    failed++
                    continue
                }
                fromCache++
                allEntries += M3uParser.parse(cached.body).map { entry ->
                    entry.copy(sourcePlaylist = filename)
                }
            }
            val filtered = SupplementDedup.filterNewChannels(
                entries = allEntries,
                daddyChannels = daddyChannels,
                maxChannels = IptvOrgStreamsConfig.MAX_CHANNELS_AFTER_DEDUP,
                importMode = importMode,
            ) { entry, _ ->
                toIptvOrgChannel(entry, entry.sourcePlaylist.orEmpty())
            }
            FetchOutcome(
                channels = filtered.channels,
                stats = FetchStats(
                    playlistsFetched = fromCache,
                    playlistsFailed = failed,
                    playlistsFromCache = fromCache,
                    entriesParsed = allEntries.size,
                    channelsAfterDedup = filtered.channels.size,
                    daddyFallbacksAttached = filtered.daddyFallbacks.values.sumOf { it.size },
                    playlistsTotal = playlistFiles.size,
                ),
                daddyFallbacks = filtered.daddyFallbacks,
            )
        }

    private fun orderPlaylists(files: List<String>): List<String> {
        val smallFirst = IptvOrgStreamsConfig.SMALL_FIRST_HINTS
        return files.sortedWith(
            compareBy<String> { smallFirst.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { playlistCache.cachedSizeBytes(it).let { sz -> if (sz > 0) sz else Long.MAX_VALUE } }
                .thenBy { it },
        )
    }

    private data class PlaylistResult(
        val filename: String,
        val entries: List<M3uParser.Entry>,
        val fromCache: Boolean,
    )

    private fun fetchPlaylist(filename: String): PlaylistResult? {
        val cached = playlistCache.fetch(filename) ?: return null
        val entries = M3uParser.parse(cached.body).map { entry ->
            entry.copy(sourcePlaylist = filename)
        }
        return PlaylistResult(filename, entries, fromCache = cached.fromCache)
    }

    private fun toIptvOrgChannel(entry: M3uParser.Entry, playlistFile: String): SupplementChannel {
        val norm = com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper.normalizeName(entry.name)
        val resolution = channelResolver.resolve(entry, playlistFile)
        val tags = channelResolver.buildTags(entry, playlistFile)
        val providerTag = IptvOrgStreamsConfig.providerTagFor(playlistFile).takeIf { it.isNotEmpty() }
        val id = "iptv:${shortHash(resolution.groupTitle + "|" + norm + "|" + entry.streamUrl)}"
        val tvgId = fastChannelTvgIdResolver?.resolve(
            displayName = entry.name.trim(),
            groupTitle = resolution.groupTitle,
            providerTag = providerTag,
            currentTvgId = entry.tvgId,
        )?.tvgId
            ?: entry.tvgId?.trim()?.takeIf { it.isNotEmpty() }
            ?: fastEpgCatalog?.lookupChannelId(entry.name, providerTag)
            ?: nameIndex?.lookupExact(entry.name)
        return SupplementChannel(
            id = id,
            name = entry.name.trim(),
            tvgId = tvgId,
            logo = entry.logo?.trim()?.takeIf { it.isNotEmpty() },
            groupTitle = resolution.groupTitle,
            streamUrl = entry.streamUrl.trim(),
            tags = tags,
            providerTag = providerTag,
            referer = null,
            origin = null,
        )
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "IptvOrgStreamsSource"
        private const val WAVE_SIZE = 5
    }
}
