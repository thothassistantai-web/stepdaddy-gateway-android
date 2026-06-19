package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.security.MessageDigest

object SupplementDedup {
    fun filterNewChannels(
        entries: List<M3uParser.Entry>,
        daddyChannels: List<Channel>,
        maxChannels: Int = SupplementConfig.MAX_CHANNELS,
        applySidecarProviderFilter: Boolean = true,
        mapChannel: (M3uParser.Entry, String) -> SupplementChannel = { entry, _ ->
            toSidecarChannel(entry)
        },
    ): List<SupplementChannel> {
        val daddyNormNames = daddyChannels
            .map { EpgChannelMapper.normalizeName(it.name) }
            .filter { it.isNotEmpty() }
            .toSet()
        val daddyTvgIds = daddyChannels
            .flatMap { channel -> tvgIdKeys(channel.tvgId) }
            .toSet()

        val candidateEntries = if (applySidecarProviderFilter) {
            SupplementProviderFilter.filter(entries).allowed
        } else {
            entries
        }

        val seenUrls = mutableSetOf<String>()
        val seenTvgIds = mutableSetOf<String>()
        val out = mutableListOf<SupplementChannel>()

        for (entry in candidateEntries) {
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

    fun providerFilterResult(entries: List<M3uParser.Entry>): SupplementProviderFilter.Result =
        SupplementProviderFilter.filter(entries)

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

    private fun toSidecarChannel(entry: M3uParser.Entry): SupplementChannel {
        val norm = EpgChannelMapper.normalizeName(entry.name)
        val id = "sup:${shortHash(norm + "|" + entry.streamUrl)}"
        val groupTitle = entry.groupTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: SupplementConfig.GROUP_PREFIX
        return SupplementChannel(
            id = id,
            name = entry.name.trim(),
            tvgId = entry.tvgId?.trim()?.takeIf { it.isNotEmpty() },
            logo = entry.logo?.trim()?.takeIf { it.isNotEmpty() },
            groupTitle = groupTitle,
            streamUrl = entry.streamUrl.trim(),
            referer = SupplementConfig.MOVEONJOY_REFERER,
            origin = SupplementConfig.MOVEONJOY_REFERER.trimEnd('/'),
        )
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
