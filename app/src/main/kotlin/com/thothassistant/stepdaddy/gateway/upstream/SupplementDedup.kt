package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

object SupplementDedup {
    data class FilterResult(
        val channels: List<SupplementChannel>,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    fun filterNewChannels(
        entries: List<M3uParser.Entry>,
        daddyChannels: List<Channel>,
        maxChannels: Int = Int.MAX_VALUE,
        importMode: SupplementImportMode = SupplementImportMode.FULL_CATALOG,
        mapChannel: (M3uParser.Entry, String) -> SupplementChannel,
    ): FilterResult {
        val consolidate = importMode.attachesFallbacks()
        val skipDuplicates = importMode.skipsDuplicateRows()
        val daddyIndexes = if (skipDuplicates) {
            SupplementImportMatcher.buildDaddyIndexes(daddyChannels)
        } else {
            SupplementImportMatcher.DaddyIndexes(emptyMap(), emptyMap())
        }

        val seenUrls = mutableSetOf<String>()
        val seenTvgIds = mutableSetOf<String>()
        val primaryByTvg = mutableMapOf<String, String>()
        val channelFallbacks = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
        val daddyFallbacks = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()
        val out = mutableListOf<SupplementChannel>()

        for (entry in entries) {
            if (out.size >= maxChannels) break
            val norm = EpgChannelMapper.normalizeName(entry.name)
            val entryTvgKeys = tvgIdKeys(entry.tvgId)

            if (skipDuplicates && SupplementImportMatcher.matchesDaddy(norm, entry.tvgId, daddyIndexes)) {
                if (consolidate) {
                    val targetId = SupplementImportMatcher.resolveDaddyChannelId(norm, entry.tvgId, daddyIndexes)
                    if (targetId != null) {
                        val mirror = mirrorFromEntry(entry, label = entry.sourcePlaylist.orEmpty())
                        daddyFallbacks.getOrPut(targetId) { mutableListOf() } += mirror
                    }
                }
                continue
            }

            val urlKey = entry.streamUrl.trim().lowercase()
            if (urlKey.isNotEmpty() && urlKey in seenUrls) {
                if (consolidate) {
                    attachInternalFallback(out, primaryByTvg, entryTvgKeys, entry)
                }
                continue
            }

            if (entryTvgKeys.isNotEmpty() && entryTvgKeys.any { it in seenTvgIds }) {
                if (consolidate) {
                    attachInternalFallback(out, primaryByTvg, entryTvgKeys, entry)
                }
                continue
            }

            val defaultGroup = entry.groupTitle?.trim().orEmpty()
            val channel = mapChannel(entry, defaultGroup)
            val withMirrors = channelFallbacks.remove(channel.id)?.let { existing ->
                channel.copy(fallbackMirrors = existing + channel.fallbackMirrors)
            } ?: channel
            out += withMirrors
            if (urlKey.isNotEmpty()) seenUrls += urlKey
            seenTvgIds += entryTvgKeys
            for (key in entryTvgKeys) {
                primaryByTvg.putIfAbsent(key, withMirrors.id)
            }
        }

        if (channelFallbacks.isNotEmpty()) {
            val merged = out.map { ch ->
                channelFallbacks[ch.id]?.let { pending ->
                    ch.copy(fallbackMirrors = pending + ch.fallbackMirrors)
                } ?: ch
            }
            return FilterResult(merged, daddyFallbacks)
        }

        return FilterResult(out, daddyFallbacks)
    }

    private fun attachInternalFallback(
        out: MutableList<SupplementChannel>,
        primaryByTvg: Map<String, String>,
        entryTvgKeys: Set<String>,
        entry: M3uParser.Entry,
    ) {
        val primaryId = entryTvgKeys.firstNotNullOfOrNull { primaryByTvg[it] } ?: return
        val index = out.indexOfFirst { it.id == primaryId }
        if (index < 0) return
        val mirror = mirrorFromEntry(entry, label = entry.sourcePlaylist.orEmpty())
        val current = out[index]
        out[index] = current.copy(fallbackMirrors = current.fallbackMirrors + mirror)
    }

    fun mirrorFromEntry(entry: M3uParser.Entry, label: String): SupplementFallbackMirror =
        SupplementFallbackMirror(
            streamUrl = entry.streamUrl.trim(),
            label = label.substringAfterLast('/').removeSuffix(".m3u"),
        )

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
