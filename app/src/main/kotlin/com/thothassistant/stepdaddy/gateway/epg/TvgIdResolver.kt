package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel

/**
 * Resolves [tvg-id] for channels not covered by [EpgChannelMapper] static maps.
 */
class TvgIdResolver(
    private val nameIndex: IptvOrgNameIndex,
    private val epgChannelMapper: EpgChannelMapper? = null,
) {
    data class Match(
        val tvgId: String,
        val confidence: Float,
        val method: String,
    )

    fun resolve(
        channelName: String,
        metaTvgId: String? = null,
        groupTitle: String? = null,
    ): Match? {
        metaTvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { meta ->
            val provider = effectiveProvider(channelName, groupTitle)
            if (provider == null || FastChannelContext.tvgIdMatchesProvider(meta, provider)) {
                return Match(meta, 1.0f, "meta")
            }
        }
        epgChannelMapper?.tvgIdForName(channelName)?.trim()?.takeIf { it.isNotEmpty() }?.let { override ->
            val provider = effectiveProvider(channelName, groupTitle)
            if (provider == null || FastChannelContext.tvgIdMatchesProvider(override, provider)) {
                return Match(override, 0.93f, "name_override")
            }
        }
        val exact = nameIndex.lookupExact(channelName)
        if (exact != null && acceptsIndexMatch(exact, channelName, groupTitle)) {
            return Match(exact, 0.95f, "exact_name")
        }
        val fuzzy = nameIndex.lookupFuzzy(channelName)
        if (fuzzy != null && acceptsIndexMatch(fuzzy, channelName, groupTitle)) {
            return Match(fuzzy, 0.75f, "fuzzy_name")
        }
        return null
    }

    private fun effectiveProvider(channelName: String, groupTitle: String?): String? =
        FastChannelContext.parseProviderFromName(channelName)
            ?: groupTitle?.let { FastChannelContext.parseProviderFromGroup(it) }

    private fun acceptsIndexMatch(tvgId: String, channelName: String, groupTitle: String?): Boolean {
        val provider = effectiveProvider(channelName, groupTitle) ?: return true
        return FastChannelContext.tvgIdMatchesProvider(tvgId, provider)
    }

    fun backfillUnmapped(
        context: Context,
        mapper: EpgChannelMapper,
        channels: List<Channel>,
        minConfidence: Float = 0.70f,
    ): Int {
        var assigned = 0
        for (channel in channels) {
            val staticMapped = mapper.tvgIdFor(channel.id, channel.name)
            if (staticMapped != null && channel.tvgId.isNullOrBlank()) continue
            val groupHint = channel.tags.firstOrNull()
            val current = channel.tvgId?.trim().orEmpty()
            if (current.isNotEmpty()) {
                val provider = effectiveProvider(channel.name, groupHint)
                if (provider == null || FastChannelContext.tvgIdMatchesProvider(current, provider)) {
                    continue
                }
            } else if (staticMapped != null) {
                continue
            }
            val match = resolve(channel.name, metaTvgId = channel.tvgId, groupTitle = groupHint) ?: continue
            if (match.confidence < minConfidence) continue
            if (match.tvgId == current) continue
            mapper.putRuntimeIdOverride(channel.id, match.tvgId)
            assigned++
        }
        if (assigned > 0) {
            mapper.saveRuntimeIdMap(context)
            Log.i(TAG, "Auto-resolved tvg-id for $assigned channels")
        }
        return assigned
    }

    companion object {
        private const val TAG = "TvgIdResolver"
    }
}
