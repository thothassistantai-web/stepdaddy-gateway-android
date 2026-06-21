package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel

/**
 * Resolves [tvg-id] for channels not covered by [EpgChannelMapper] static maps.
 */
class TvgIdResolver(
    private val nameIndex: IptvOrgNameIndex,
) {
    data class Match(
        val tvgId: String,
        val confidence: Float,
        val method: String,
    )

    fun resolve(channelName: String, metaTvgId: String? = null): Match? {
        metaTvgId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Match(it, 1.0f, "meta")
        }
        val exact = nameIndex.lookupExact(channelName)
        if (exact != null) {
            return Match(exact, 0.95f, "exact_name")
        }
        val fuzzy = nameIndex.lookupFuzzy(channelName)
        if (fuzzy != null) {
            return Match(fuzzy, 0.75f, "fuzzy_name")
        }
        return null
    }

    fun backfillUnmapped(
        context: Context,
        mapper: EpgChannelMapper,
        channels: List<Channel>,
        minConfidence: Float = 0.70f,
    ): Int {
        var assigned = 0
        for (channel in channels) {
            if (!channel.tvgId.isNullOrBlank()) continue
            if (mapper.tvgIdFor(channel.id, channel.name) != null) continue
            val match = resolve(channel.name) ?: continue
            if (match.confidence < minConfidence) continue
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
