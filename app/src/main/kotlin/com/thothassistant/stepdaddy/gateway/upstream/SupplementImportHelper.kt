package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

object SupplementImportHelper {
    data class RowDecision(
        val publish: Boolean,
        val daddyFallback: Pair<String, SupplementFallbackMirror>? = null,
    )

    /**
     * Catalog publish follows [importMode]; daddy fallback attachment always runs for high-confidence
     * overlaps so Smart playlist failover works under FULL_CATALOG.
     */
    fun evaluateDaddyOverlap(
        name: String,
        tvgId: String?,
        daddyChannels: List<Channel>,
        importMode: SupplementImportMode,
        mirror: SupplementFallbackMirror,
        tags: List<String> = emptyList(),
        countryHint: String? = null,
        sourcePlaylist: String? = null,
    ): RowDecision {
        val indexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        if (!SupplementImportMatcher.matchesDaddy(
                name = name,
                tvgId = tvgId,
                indexes = indexes,
                tags = tags,
                countryHint = countryHint,
                sourcePlaylist = sourcePlaylist,
            )
        ) {
            return RowDecision(publish = true)
        }
        val targetId = SupplementImportMatcher.resolveDaddyChannelId(
            name = name,
            tvgId = tvgId,
            indexes = indexes,
            tags = tags,
            countryHint = countryHint,
            sourcePlaylist = sourcePlaylist,
        )
        val fallback = targetId?.let { it to mirror }
        if (!importMode.skipsDuplicateRows()) {
            return RowDecision(publish = true, daddyFallback = fallback)
        }
        return RowDecision(publish = false, daddyFallback = fallback)
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
