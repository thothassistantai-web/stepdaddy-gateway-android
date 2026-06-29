package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

object SupplementDedup {
    fun filterNewChannels(
        entries: List<M3uParser.Entry>,
        daddyChannels: List<Channel>,
        maxChannels: Int = Int.MAX_VALUE,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        mapChannel: (M3uParser.Entry, String) -> SupplementChannel,
    ): List<SupplementChannel> {
        val skipDuplicates = importMode == SupplementImportMode.SKIP_DUPLICATES
        val daddyNormNames = if (skipDuplicates) {
            daddyChannels
                .map { EpgChannelMapper.normalizeName(it.name) }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            emptySet()
        }
        val daddyTvgIds = if (skipDuplicates) {
            daddyChannels
                .flatMap { channel -> tvgIdKeys(channel.tvgId) }
                .toSet()
        } else {
            emptySet()
        }

        val seenUrls = mutableSetOf<String>()
        val seenTvgIds = mutableSetOf<String>()
        val out = mutableListOf<SupplementChannel>()

        for (entry in entries) {
            if (out.size >= maxChannels) break
            val norm = EpgChannelMapper.normalizeName(entry.name)
            if (norm in daddyNormNames) continue
            if (matchesDaddyTvgId(entry.tvgId, daddyTvgIds)) continue

            val urlKey = entry.streamUrl.trim().lowercase()
            if (urlKey.isNotEmpty() && urlKey in seenUrls) continue

            val entryTvgKeys = tvgIdKeys(entry.tvgId)
            if (entryTvgKeys.isNotEmpty() && entryTvgKeys.any { it in seenTvgIds }) continue

            val defaultGroup = entry.groupTitle?.trim().orEmpty()
            out += mapChannel(entry, defaultGroup)
            if (urlKey.isNotEmpty()) seenUrls += urlKey
            seenTvgIds += entryTvgKeys
        }
        return out
    }

    fun matchesDaddyTvgId(entryTvgId: String?, daddyTvgIds: Set<String>): Boolean =
        tvgIdKeys(entryTvgId).any { it in daddyTvgIds }

    fun tvgIdKeys(tvgId: String?): Set<String> {
        val raw = tvgId?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return emptySet()
        val base = raw.substringBefore('@')
        return buildSet {
            add(raw)
            if (base.isNotEmpty()) add(base)
        }
    }
}
