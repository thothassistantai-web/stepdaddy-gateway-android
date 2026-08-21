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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches UK/US iptv-org stream playlists from GitHub on each supplement sync.
 */
class IptvOrgStreamsSource(
    context: Context,
    private val httpClient: OkHttpClient,
    private val channelResolver: IptvOrgChannelResolver = IptvOrgChannelResolver(context),
    private val fastEpgCatalog: FastEpgCatalog? = null,
    private val fastChannelTvgIdResolver: FastChannelTvgIdResolver? = null,
    private val nameIndex: com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex? = null,
) {
    data class FetchStats(
        val playlistsFetched: Int = 0,
        val playlistsFailed: Int = 0,
        val entriesParsed: Int = 0,
        val channelsAfterDedup: Int = 0,
        val daddyFallbacksAttached: Int = 0,
    )

    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: FetchStats,
        val daddyFallbacks: Map<String, List<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>> = emptyMap(),
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.CONSOLIDATE_FALLBACKS,
        enabledPlaylists: Set<String> = IptvOrgStreamsConfig.PLAYLIST_FILES.toSet(),
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val playlistFiles = IptvOrgStreamsConfig.PLAYLIST_FILES.filter { it in enabledPlaylists }
            if (playlistFiles.isEmpty()) {
                return@withContext FetchOutcome(emptyList(), FetchStats())
            }
            val semaphore = Semaphore(4)
            val parsed = coroutineScope {
                playlistFiles.map { filename ->
                    async {
                        semaphore.withPermit {
                            fetchPlaylist(filename)
                        }
                    }
                }.awaitAll()
            }

            var fetched = 0
            var failed = 0
            val allEntries = mutableListOf<M3uParser.Entry>()
            parsed.forEach { result ->
                if (result == null) {
                    failed++
                } else {
                    fetched++
                    allEntries += result.entries
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
                    playlistsFetched = fetched,
                    playlistsFailed = failed,
                    entriesParsed = allEntries.size,
                    channelsAfterDedup = filtered.channels.size,
                    daddyFallbacksAttached = filtered.daddyFallbacks.values.sumOf { it.size },
                ),
                daddyFallbacks = filtered.daddyFallbacks,
            )
        }

    private data class PlaylistResult(
        val filename: String,
        val entries: List<M3uParser.Entry>,
    )

    private fun fetchPlaylist(filename: String): PlaylistResult? {
        val url = IptvOrgStreamsConfig.rawUrl(filename)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SupplementConfig.USER_AGENT)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "iptv-org fetch failed ${response.code} $filename")
                    return null
                }
                val body = response.body ?: return null
                val bytes = body.bytes()
                if (bytes.isEmpty()) return null
                if (bytes.size > IptvOrgStreamsConfig.MAX_BYTES_PER_PLAYLIST) {
                    Log.w(TAG, "iptv-org playlist too large: $filename (${bytes.size} bytes)")
                    return null
                }
                val text = bytes.toString(Charsets.UTF_8)
                val entries = M3uParser.parse(text).map { entry ->
                    entry.copy(sourcePlaylist = filename)
                }
                PlaylistResult(filename, entries)
            }
        }.getOrElse { exc ->
            Log.w(TAG, "iptv-org fetch error $filename", exc)
            null
        }
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
    }
}
