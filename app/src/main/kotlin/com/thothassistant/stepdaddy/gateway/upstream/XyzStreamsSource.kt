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
        val daddyFallbacksAttached: Int = 0,
    )

    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: FetchStats,
        val daddyFallbacks: Map<String, List<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>> = emptyMap(),
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        enableDiscovery: Boolean = true,
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val consolidate = importMode.attachesFallbacks()
        val skipDuplicates = importMode.skipsDuplicateRows()
        val daddyIndexes = if (skipDuplicates) {
            SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        } else {
            SupplementImportMatcher.DaddyIndexes(emptyMap(), emptyMap())
        }
        val daddyFallbacks = mutableMapOf<String, MutableList<com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror>>()

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
            if (skipDuplicates && SupplementImportMatcher.matchesDaddy(norm, row.tvgId, daddyIndexes)) {
                if (consolidate) {
                    val targetId = SupplementImportMatcher.resolveDaddyChannelId(norm, row.tvgId, daddyIndexes)
                    if (targetId != null) {
                        daddyFallbacks.getOrPut(targetId) { mutableListOf() } += com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror(
                            streamUrl = XyzStreamsConfig.upstreamManifestUrl(row),
                            label = XyzStreamsConfig.PROVIDER_TAG,
                            referer = XyzStreamsConfig.REFERER,
                            origin = XyzStreamsConfig.ORIGIN,
                        )
                    }
                }
                return@mapNotNull null
            }
            toSupplementChannel(row)
        }

        val publishedDiscoveredLabels = channels.mapNotNull { ch ->
            val streamId = ch.id.removePrefix("xyz:")
            if (streamId.lowercase() in discoveredIds) "${ch.name} ($streamId)" else null
        }.sorted()
        val catalogPublished = channels.size - publishedDiscoveredLabels.size

        FetchOutcome(
            channels = channels,
            stats = FetchStats(
                catalogRows = staticRows.size,
                catalogPublished = catalogPublished,
                discoveredRows = discovered.size,
                discoveredPublished = publishedDiscoveredLabels.size,
                discoveryProbes = discoveryStats.candidatesProbed,
                discoveredChannelLabels = publishedDiscoveredLabels,
                channelsAfterDedup = channels.size,
                epgDiscoveryEnabled = enableDiscovery,
                daddyFallbacksAttached = daddyFallbacks.values.sumOf { it.size },
            ),
            daddyFallbacks = daddyFallbacks,
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
