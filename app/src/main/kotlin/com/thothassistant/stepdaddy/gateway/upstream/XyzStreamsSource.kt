package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes xyzstreams.st channel catalog as gateway supplement rows.
 * Merges verified static catalog rows with EPG-driven discovery probes.
 */
class XyzStreamsSource(
    private val discovery: XyzStreamsDiscovery = XyzStreamsDiscovery(),
) {
    data class FetchStats(
        val catalogRows: Int = 0,
        val catalogPublished: Int = 0,
        val discoveredRows: Int = 0,
        val discoveredPublished: Int = 0,
        val discoveryProbes: Int = 0,
        val discoveredChannelLabels: List<String> = emptyList(),
        val channelsAfterDedup: Int = 0,
        val epgDiscoveryEnabled: Boolean = true,
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        enableDiscovery: Boolean = true,
    ): Pair<List<SupplementChannel>, FetchStats> = withContext(Dispatchers.IO) {
        val skipDuplicates = importMode == SupplementImportMode.SKIP_DUPLICATES
        val daddyNormNames = if (skipDuplicates) {
            daddyChannels
                .map { EpgChannelMapper.normalizeName(it.name) }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            emptySet()
        }

        val staticRows = XyzStreamsCatalog.CATALOG
        val (discovered, discoveryStats) = if (enableDiscovery) {
            discovery.discoverAdditionalChannels(staticRows)
        } else {
            emptyList<XyzStreamsConfig.ChannelRow>() to XyzStreamsDiscovery.Stats()
        }

        val discoveredIds = discovered.map { it.streamId.lowercase() }.toSet()
        val allRows = (staticRows + discovered).distinctBy { it.streamId.lowercase() }
        val channels = allRows.mapNotNull { row ->
            val shouldProbe = row.streamId.lowercase() in discoveredIds
            if (shouldProbe && !XyzStreamsProbe.isLiveManifest(row.streamId, row.proId)) {
                Log.d(TAG, "xyz discovery probe skip: ${row.streamId}")
                return@mapNotNull null
            }
            val norm = EpgChannelMapper.normalizeName(row.displayName)
            if (skipDuplicates && norm in daddyNormNames) return@mapNotNull null
            toSupplementChannel(row)
        }

        val publishedDiscoveredLabels = channels.mapNotNull { ch ->
            val streamId = ch.id.removePrefix("xyz:")
            if (streamId.lowercase() in discoveredIds) "${ch.name} ($streamId)" else null
        }.sorted()
        val catalogPublished = channels.size - publishedDiscoveredLabels.size

        channels to FetchStats(
            catalogRows = staticRows.size,
            catalogPublished = catalogPublished,
            discoveredRows = discovered.size,
            discoveredPublished = publishedDiscoveredLabels.size,
            discoveryProbes = discoveryStats.candidatesProbed,
            discoveredChannelLabels = publishedDiscoveredLabels,
            channelsAfterDedup = channels.size,
            epgDiscoveryEnabled = enableDiscovery,
        )
    }

    private fun toSupplementChannel(row: XyzStreamsConfig.ChannelRow): SupplementChannel =
        SupplementChannel(
            id = "xyz:${row.streamId}",
            name = row.displayName,
            tvgId = row.tvgId,
            logo = row.logo,
            groupTitle = row.groupTitle,
            streamUrl = XyzStreamsConfig.upstreamManifestUrl(row),
            providerTag = XyzStreamsConfig.PROVIDER_TAG,
            referer = XyzStreamsConfig.REFERER,
            origin = XyzStreamsConfig.ORIGIN,
            tags = listOf("#us", "#xyzstreams"),
        )

    companion object {
        private const val TAG = "XyzStreamsSource"
    }
}
