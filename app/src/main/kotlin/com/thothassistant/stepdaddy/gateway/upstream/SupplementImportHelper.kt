package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

object SupplementImportHelper {
    data class RowDecision(
        val publish: Boolean,
        val daddyFallback: Pair<String, SupplementFallbackMirror>? = null,
    )

    fun evaluateDaddyOverlap(
        name: String,
        tvgId: String?,
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode,
        mirror: SupplementFallbackMirror,
    ): RowDecision {
        if (!importMode.skipsDuplicateRows()) {
            return RowDecision(publish = true)
        }
        val indexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        val norm = EpgChannelMapper.normalizeName(name)
        if (!SupplementImportMatcher.matchesDaddy(norm, tvgId, indexes)) {
            return RowDecision(publish = true)
        }
        if (importMode.attachesFallbacks()) {
            val targetId = SupplementImportMatcher.resolveDaddyChannelId(norm, tvgId, indexes)
            if (targetId != null) {
                return RowDecision(publish = false, daddyFallback = targetId to mirror)
            }
        }
        return RowDecision(publish = false)
    }

    fun mergeDaddyFallbackMaps(
        vararg maps: Map<String, List<SupplementFallbackMirror>>,
    ): Map<String, List<SupplementFallbackMirror>> {
        val merged = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
        for (map in maps) {
            for ((channelId, mirrors) in map) {
                merged.getOrPut(channelId) { mutableListOf() }.addAll(mirrors)
            }
        }
        return merged
    }
}
