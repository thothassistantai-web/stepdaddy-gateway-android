package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.SupplementTvgIdResolver
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches dulo.cx Live TV catalog and merges as a supplement tier (and DaddyLive backups).
 * Streams resolve on play via [DuloCxLiveResolver] (`duloChannelId`).
 */
class DuloCxLiveSource(
    private val resolver: DuloCxLiveResolver,
) {
    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: DuloCxLiveResolver.FetchStats,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode,
        nameIndex: IptvOrgNameIndex? = null,
        authConfigured: Boolean = false,
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val catalog = resolver.fetchCatalog()
        val catalogOk = catalog.isNotEmpty()
        val built = buildChannels(
            catalog = catalog,
            daddyChannels = daddyChannels,
            importMode = importMode,
            nameIndex = nameIndex,
        )
        val channels = built.channels

        val probeOk = if (authConfigured && channels.isNotEmpty()) {
            runCatching {
                val id = channels.first().duloChannelId?.trim().orEmpty()
                if (id.isEmpty()) return@runCatching false
                resolver.resolveManifestUrl(id)
                true
            }.getOrElse { exc ->
                Log.w(TAG, "dulo.cx resolve probe failed — keeping catalog", exc)
                false
            }
        } else {
            false
        }

        val supporterSkipped = catalog.count { it.supporterOnly }
        FetchOutcome(
            channels = channels,
            stats = DuloCxLiveResolver.FetchStats(
                catalogRows = catalog.size,
                playableRows = catalog.count { it.playable && !it.supporterOnly },
                supporterSkipped = supporterSkipped,
                channelsAfterDedup = channels.size,
                catalogFetchOk = catalogOk,
                resolveProbeOk = probeOk,
                authConfigured = authConfigured,
            ),
            daddyFallbacks = built.daddyFallbacks,
        )
    }

    data class BuildResult(
        val channels: List<SupplementChannel>,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    companion object {
        private const val TAG = "DuloCxLiveSource"

        fun buildChannels(
            catalog: List<DuloCxLiveResolver.CatalogChannel>,
            daddyChannels: List<Channel>,
            importMode: SupplementImportMode,
            nameIndex: IptvOrgNameIndex? = null,
            maxChannels: Int = DuloCxLiveConfig.MAX_CHANNELS,
        ): BuildResult {
            val skipDuplicates = importMode.skipsDuplicateRows()
            // Always index Daddy for Smart failover attachments, independent of catalog import mode.
            val daddyIndexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)

            val ranked = catalog
                .filter { it.playable && !it.supporterOnly && it.id.isNotBlank() }
                .sortedWith(
                    compareByDescending<DuloCxLiveResolver.CatalogChannel> { rank(it) }
                        .thenBy { it.sortOrder }
                        .thenBy { it.name.lowercase() },
                )

            val daddyFallbacks = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
            val seenIds = mutableSetOf<String>()
            val channels = mutableListOf<SupplementChannel>()

            for (row in ranked) {
                if (channels.size >= maxChannels) break
                if (!seenIds.add(row.id)) continue

                val displayName = cleanDisplayName(row.name)
                val regionTag = DuloCxLiveConfig.regionTagFromName(row.name)
                val countryHint = SupplementMatchScorer.normalizeRegion(regionTag.removePrefix("#"))
                if (SupplementImportMatcher.matchesDaddy(
                        name = displayName,
                        tvgId = null,
                        indexes = daddyIndexes,
                        tags = listOf(regionTag),
                        countryHint = countryHint,
                    )
                ) {
                    val targetId = SupplementImportMatcher.resolveDaddyChannelId(
                        name = displayName,
                        tvgId = null,
                        indexes = daddyIndexes,
                        tags = listOf(regionTag),
                        countryHint = countryHint,
                    )
                    if (targetId != null) {
                        daddyFallbacks.getOrPut(targetId) { mutableListOf() } += SupplementFallbackMirror(
                            label = DuloCxLiveConfig.PROVIDER_TAG,
                            referer = DuloCxLiveConfig.REFERER,
                            origin = DuloCxLiveConfig.ORIGIN,
                            duloChannelId = row.id,
                        )
                    }
                    if (skipDuplicates) continue
                }

                val categoryTag = DuloCxLiveConfig.categoryTag(row.category)
                val tags = listOf(regionTag, categoryTag, "#dulo", "#live")
                val resolution = GroupTitleResolver.resolve(
                    channelName = displayName,
                    tags = tags,
                    channelId = null,
                )
                channels += SupplementChannel(
                    id = "${DuloCxLiveConfig.ID_PREFIX}${row.id}",
                    name = displayName,
                    tvgId = nameIndex?.let { SupplementTvgIdResolver.forChannelName(it, displayName) },
                    logo = row.logoUrl,
                    groupTitle = resolution.groupTitle.ifBlank { DuloCxLiveConfig.GROUP_TITLE },
                    streamUrl = "",
                    tags = tags,
                    providerTag = DuloCxLiveConfig.PROVIDER_TAG,
                    referer = DuloCxLiveConfig.REFERER,
                    origin = DuloCxLiveConfig.ORIGIN,
                    duloChannelId = row.id,
                )
            }
            return BuildResult(channels, daddyFallbacks)
        }

        fun rank(row: DuloCxLiveResolver.CatalogChannel): Int {
            var score = DuloCxLiveConfig.CATEGORY_PRIORITY[row.category.lowercase()] ?: 0
            val upper = row.name.uppercase()
            if (upper.contains("| USA") || upper.endsWith(" USA")) score += 40
            if (upper.contains("| UK") || upper.contains("| CA")) score += 15
            if (upper.startsWith("24/7")) score -= 25
            return score
        }

        fun cleanDisplayName(raw: String): String =
            raw.trim()
                .replace(Regex("""\s*\|\s*(USA|UK|CA|AU|LAT|MEX|ES|PT)\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+HD\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifEmpty { raw.trim() }
    }
}
