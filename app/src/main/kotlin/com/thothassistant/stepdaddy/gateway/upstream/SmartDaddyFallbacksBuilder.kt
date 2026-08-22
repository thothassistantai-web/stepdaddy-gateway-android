package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

/**
 * Builds DaddyLive → supplement failover maps from **published** catalog rows.
 *
 * Used so `/tivimate-smart` always has merge-style backups under FULL_CATALOG, and so
 * timeout/cache-recovery paths still attach mirrors when fetch-time consolidate maps
 * were never written.
 */
object SmartDaddyFallbacksBuilder {
    fun fromSupplements(
        daddyChannels: List<Channel>,
        supplements: List<SupplementChannel>,
    ): Map<String, List<SupplementFallbackMirror>> {
        if (daddyChannels.isEmpty() || supplements.isEmpty()) return emptyMap()
        val indexes = SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        val out = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
        for (channel in supplements) {
            if (!isEligibleBackupSource(channel)) continue
            if (!SupplementImportMatcher.matchesDaddy(
                    name = channel.name,
                    tvgId = channel.tvgId,
                    indexes = indexes,
                    tags = channel.tags,
                    countryHint = countryHintFor(channel),
                )
            ) {
                continue
            }
            val targetId = SupplementImportMatcher.resolveDaddyChannelId(
                name = channel.name,
                tvgId = channel.tvgId,
                indexes = indexes,
                tags = channel.tags,
                countryHint = countryHintFor(channel),
            ) ?: continue
            out.getOrPut(targetId) { mutableListOf() } +=
                SupplementFallbackMirrorFactory.fromSupplement(channel)
        }
        return out.mapValues { (_, mirrors) ->
            mirrors.distinctBy { SupplementMatchScorer.mirrorFingerprint(it) }
        }
    }

    fun countryHintFor(channel: SupplementChannel): String? {
        channel.regionCode?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val ntvKey = channel.ntvCdnLiveKey?.trim().orEmpty()
        if (ntvKey.startsWith("cdnlive|")) {
            val region = ntvKey.split('|').getOrNull(2)?.trim().orEmpty()
            if (region.isNotEmpty() && !region.startsWith("http", ignoreCase = true)) return region
        }
        return SupplementMatchScorer.extractSignals(
            name = channel.name,
            tvgId = channel.tvgId,
            tags = channel.tags,
        ).region
    }

    fun isEligibleBackupSource(channel: SupplementChannel): Boolean {
        val id = channel.id
        if (id.startsWith("dlhd-guide:") || id.startsWith("dlhd-event:")) return false
        if (id.startsWith("tmdb:") || id.startsWith("vod:") || id.startsWith("series:")) return false
        // Need a resolvable upstream (URL and/or provider key).
        return channel.streamUrl.isNotBlank() ||
            !channel.ntvCdnLiveKey.isNullOrBlank() ||
            !channel.duloChannelId.isNullOrBlank()
    }
}
