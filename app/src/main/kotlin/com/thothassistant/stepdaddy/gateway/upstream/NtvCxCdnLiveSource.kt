package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
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
        mergeMode: NtvCxMergeMode,
    ): Pair<List<SupplementChannel>, NtvCxCdnLiveResolver.FetchStats> =
        withContext(Dispatchers.IO) {
            val catalog = resolver.fetchCatalog()
            val channels = buildChannels(
                catalog = catalog,
                daddyChannels = daddyChannels,
                mergeMode = mergeMode,
            )

            val probeOk = runCatching {
                if (channels.isEmpty()) return@runCatching false
                val first = channels.first()
                val parts = first.ntvCdnLiveKey?.split("|", limit = 2) ?: return@runCatching false
                if (parts.size != 2) return@runCatching false
                resolver.resolveManifestUrl(parts[0], parts[1])
                true
            }.getOrElse { exc ->
                Log.w(TAG, "ntv.cx CDN Live probe failed — keeping catalog", exc)
                false
            }

            channels to NtvCxCdnLiveResolver.FetchStats(
                catalogRows = catalog.size,
                cdnLiveRows = catalog.size,
                channelsAfterDedup = channels.size,
                resolveProbeOk = probeOk,
            )
        }

    companion object {
        private const val TAG = "NtvCxCdnLiveSource"
        private const val PROVIDER_TAG = "CDN"

        fun buildChannels(
            catalog: List<NtvCxCdnLiveResolver.CatalogChannel>,
            daddyChannels: List<Channel>,
            mergeMode: NtvCxMergeMode,
        ): List<SupplementChannel> {
            val daddyNormNames = if (mergeMode == NtvCxMergeMode.SUPPLEMENT_ONLY) {
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
                if (mergeMode == NtvCxMergeMode.SUPPLEMENT_ONLY && norm in daddyNormNames) continue
                val key = NtvCxCdnLiveResolver.cdnLiveKey(row.name, row.regionCode)
                if (!seenKeys.add(key)) continue
                val id = "ntv:${shortHash(key)}"
                channels += SupplementChannel(
                    id = id,
                    name = row.name,
                    tvgId = null,
                    logo = row.logo,
                    groupTitle = NtvCxCdnLiveConfig.GROUP_TITLE,
                    streamUrl = "",
                    providerTag = PROVIDER_TAG,
                    referer = NtvCxCdnLiveConfig.REFERER,
                    origin = NtvCxCdnLiveConfig.ORIGIN,
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
