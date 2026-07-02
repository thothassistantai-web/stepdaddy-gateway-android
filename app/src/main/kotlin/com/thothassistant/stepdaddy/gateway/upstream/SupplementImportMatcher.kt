package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel

/** Indexes DaddyLive rows for supplement import modes (skip / fallback attach). */
object SupplementImportMatcher {
    data class DaddyIndexes(
        val normToChannelId: Map<String, String>,
        val tvgToChannelId: Map<String, String>,
    )

    fun buildDaddyIndexes(daddyChannels: List<Channel>): DaddyIndexes {
        val normToId = mutableMapOf<String, String>()
        val tvgToId = mutableMapOf<String, String>()
        for (channel in daddyChannels) {
            val norm = EpgChannelMapper.normalizeName(channel.name)
            if (norm.isNotEmpty()) {
                normToId.putIfAbsent(norm, channel.id)
            }
            for (key in SupplementDedup.tvgIdKeys(channel.tvgId)) {
                tvgToId.putIfAbsent(key, channel.id)
            }
        }
        return DaddyIndexes(normToId, tvgToId)
    }

    fun resolveDaddyChannelId(
        normName: String,
        tvgId: String?,
        indexes: DaddyIndexes,
    ): String? {
        indexes.normToChannelId[normName]?.let { return it }
        for (key in SupplementDedup.tvgIdKeys(tvgId)) {
            indexes.tvgToChannelId[key]?.let { return it }
        }
        return null
    }

    fun matchesDaddy(
        normName: String,
        tvgId: String?,
        indexes: DaddyIndexes,
    ): Boolean = resolveDaddyChannelId(normName, tvgId, indexes) != null
}
