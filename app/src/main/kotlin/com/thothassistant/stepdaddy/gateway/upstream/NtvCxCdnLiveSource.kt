package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.SupplementTvgIdResolver
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NtvCxCdnLiveSource(
    private val resolver: NtvCxCdnLiveResolver,
) {
    data class FetchOutcome(
        val channels: List<SupplementChannel>,
        val stats: NtvCxCdnLiveResolver.FetchStats,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        mergeMode: SupplementImportMode,
        nameIndex: IptvOrgNameIndex? = null,
    ): FetchOutcome =
        withContext(Dispatchers.IO) {
            val catalog = resolver.fetchCatalog()
            val built = buildChannels(
                catalog = catalog,
                daddyChannels = daddyChannels,
                mergeMode = mergeMode,
                nameIndex = nameIndex,
            )
            val channels = built.channels

            val probeOk = runCatching {
                if (channels.isEmpty()) return@runCatching false
                var probed = false
                channels.firstOrNull { it.ntvCdnLiveKey?.startsWith("cdnlive|") == true }
                    ?.ntvCdnLiveKey
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { key ->
                        resolver.resolveManifestUrl(key)
                        probed = true
                    }
                channels.firstOrNull { it.ntvCdnLiveKey?.startsWith("hesgoales|") == true }
                    ?.ntvCdnLiveKey
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { key ->
                        resolver.resolveManifestUrl(key)
                        probed = true
                    }
                if (!probed) {
                    val key = channels.first().ntvCdnLiveKey?.trim().orEmpty()
                    if (key.isEmpty()) return@runCatching false
                    resolver.resolveManifestUrl(key)
                }
                true
            }.getOrElse { exc ->
                Log.w(TAG, "ntv.cx 24/7 probe failed — keeping catalog", exc)
                false
            }

            FetchOutcome(
                channels = channels,
                stats = NtvCxCdnLiveResolver.FetchStats(
                    catalogRows = catalog.size,
                    cdnLiveRows = catalog.count { it.server == "cdnlive" },
                    hesgoalesRows = catalog.count { it.server == "hesgoales" },
                    channelsAfterDedup = channels.size,
                    resolveProbeOk = probeOk,
                ),
                daddyFallbacks = built.daddyFallbacks,
            )
        }

    data class BuildResult(
        val channels: List<SupplementChannel>,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    companion object {
        private const val TAG = "NtvCxCdnLiveSource"
        private const val PROVIDER_TAG_CDN = "CDN"
        private const val PROVIDER_TAG_FALCON = "Falcon"

        fun buildChannels(
            catalog: List<NtvCxCdnLiveResolver.CatalogChannel>,
            daddyChannels: List<Channel>,
            mergeMode: SupplementImportMode,
            nameIndex: IptvOrgNameIndex? = null,
        ): BuildResult {
            val consolidate = mergeMode.attachesFallbacks()
            val skipDuplicates = mergeMode.skipsDuplicateRows()
            val daddyIndexes = if (skipDuplicates) {
                SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
            } else {
                SupplementImportMatcher.DaddyIndexes(emptyMap(), emptyMap())
            }
            val daddyFallbacks = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
            val seenKeys = mutableSetOf<String>()
            val channels = mutableListOf<SupplementChannel>()
            for (row in catalog) {
                if (channels.size >= NtvCxCdnLiveConfig.MAX_CHANNELS) break
                val norm = EpgChannelMapper.normalizeName(row.name)
                if (skipDuplicates && SupplementImportMatcher.matchesDaddy(norm, null, daddyIndexes)) {
                    if (consolidate) {
                        val targetId = SupplementImportMatcher.resolveDaddyChannelId(norm, null, daddyIndexes)
                        if (targetId != null) {
                            val key = NtvCxCdnLiveResolver.ntvKey(
                                server = row.server,
                                name = row.name,
                                regionCode = row.regionCode,
                                streamPageUrl = row.streamPageUrl,
                            )
                            val (referer, origin, providerTag) = ntvMirrorHeaders(row.server)
                            daddyFallbacks.getOrPut(targetId) { mutableListOf() } += SupplementFallbackMirror(
                                label = providerTag,
                                referer = referer,
                                origin = origin,
                                ntvCdnLiveKey = key,
                            )
                        }
                    }
                    continue
                }
                val key = NtvCxCdnLiveResolver.ntvKey(
                    server = row.server,
                    name = row.name,
                    regionCode = row.regionCode,
                    streamPageUrl = row.streamPageUrl,
                )
                if (!seenKeys.add(key)) continue
                val id = "ntv:${shortHash(key)}"
                val (referer, origin, providerTag) = ntvMirrorHeaders(row.server)
                channels += SupplementChannel(
                    id = id,
                    name = row.name,
                    tvgId = nameIndex?.let { SupplementTvgIdResolver.forChannelName(it, row.name) },
                    logo = row.logo,
                    groupTitle = NtvCxCdnLiveConfig.GROUP_TITLE,
                    streamUrl = "",
                    providerTag = providerTag,
                    referer = referer,
                    origin = origin,
                    ntvCdnLiveKey = key,
                )
            }
            return BuildResult(channels, daddyFallbacks)
        }

        private fun ntvMirrorHeaders(server: String): Triple<String, String, String> =
            when (server) {
                "hesgoales" -> Triple(
                    NtvCxCdnLiveConfig.HESGOALES_REFERER,
                    NtvCxCdnLiveConfig.HESGOALES_ORIGIN,
                    PROVIDER_TAG_FALCON,
                )
                else -> Triple(
                    NtvCxCdnLiveConfig.REFERER,
                    NtvCxCdnLiveConfig.ORIGIN,
                    PROVIDER_TAG_CDN,
                )
            }

        private fun shortHash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
