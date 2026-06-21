package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.epg.IptvOrgNameIndex
import com.thothassistant.stepdaddy.gateway.epg.SupplementTvgIdResolver
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NtvCxCdnLiveSource(
    private val resolver: NtvCxCdnLiveResolver,
) {
    suspend fun fetchChannels(
        daddyChannels: List<Channel>,
        mergeMode: SupplementImportMode,
        nameIndex: IptvOrgNameIndex? = null,
    ): Pair<List<SupplementChannel>, NtvCxCdnLiveResolver.FetchStats> =
        withContext(Dispatchers.IO) {
            val catalog = resolver.fetchCatalog()
            val channels = buildChannels(
                catalog = catalog,
                daddyChannels = daddyChannels,
                mergeMode = mergeMode,
                nameIndex = nameIndex,
            )

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

            channels to NtvCxCdnLiveResolver.FetchStats(
                catalogRows = catalog.size,
                cdnLiveRows = catalog.count { it.server == "cdnlive" },
                hesgoalesRows = catalog.count { it.server == "hesgoales" },
                channelsAfterDedup = channels.size,
                resolveProbeOk = probeOk,
            )
        }

    companion object {
        private const val TAG = "NtvCxCdnLiveSource"
        private const val PROVIDER_TAG_CDN = "CDN"
        private const val PROVIDER_TAG_FALCON = "Falcon"

        fun buildChannels(
            catalog: List<NtvCxCdnLiveResolver.CatalogChannel>,
            daddyChannels: List<Channel>,
            mergeMode: SupplementImportMode,
            nameIndex: IptvOrgNameIndex? = null,
        ): List<SupplementChannel> {
            val skipDuplicates = mergeMode == SupplementImportMode.SKIP_DUPLICATES
            val daddyNormNames = if (skipDuplicates) {
                daddyChannels
                    .map { EpgChannelMapper.normalizeName(it.name) }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } else {
                emptySet()
            }
            val seenKeys = mutableSetOf<String>()
            val channels = mutableListOf<SupplementChannel>()
            for (row in catalog) {
                if (channels.size >= NtvCxCdnLiveConfig.MAX_CHANNELS) break
                val norm = EpgChannelMapper.normalizeName(row.name)
                if (skipDuplicates && norm in daddyNormNames) continue
                val key = NtvCxCdnLiveResolver.ntvKey(
                    server = row.server,
                    name = row.name,
                    regionCode = row.regionCode,
                    streamPageUrl = row.streamPageUrl,
                )
                if (!seenKeys.add(key)) continue
                val id = "ntv:${shortHash(key)}"
                val (referer, origin, providerTag) = when (row.server) {
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
            return channels
        }

        private fun shortHash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
