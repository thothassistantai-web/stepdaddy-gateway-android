package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.Channel

/** Indexes DaddyLive rows for supplement import modes (skip / fallback attach). */
object SupplementImportMatcher {
    data class DaddyMatchTarget(
        val id: String,
        val name: String,
        val tvgId: String? = null,
        val tags: List<String> = emptyList(),
    )

    data class DaddyIndexes(
        val byCoreName: Map<String, List<DaddyMatchTarget>> = emptyMap(),
        val byTvg: Map<String, DaddyMatchTarget> = emptyMap(),
        val byLegacyNorm: Map<String, List<DaddyMatchTarget>> = emptyMap(),
        val all: List<DaddyMatchTarget> = emptyList(),
    )

    fun emptyIndexes(): DaddyIndexes = DaddyIndexes()

    fun buildDaddyIndexes(daddyChannels: List<Channel>): DaddyIndexes {
        val byCore = mutableMapOf<String, MutableList<DaddyMatchTarget>>()
        val byTvg = mutableMapOf<String, DaddyMatchTarget>()
        val byLegacy = mutableMapOf<String, MutableList<DaddyMatchTarget>>()
        val all = mutableListOf<DaddyMatchTarget>()
        for (channel in daddyChannels) {
            val target = DaddyMatchTarget(
                id = channel.id,
                name = channel.name,
                tvgId = channel.tvgId,
                tags = channel.tags,
            )
            all += target
            val signals = SupplementMatchScorer.extractSignals(
                name = channel.name,
                tvgId = channel.tvgId,
                tags = channel.tags,
            )
            if (signals.coreName.isNotEmpty()) {
                byCore.getOrPut(signals.coreName) { mutableListOf() } += target
            }
            val legacy = EpgChannelMapper.normalizeName(channel.name)
            if (legacy.isNotEmpty()) {
                byLegacy.getOrPut(legacy) { mutableListOf() } += target
            }
            for (key in SupplementDedup.tvgIdKeys(channel.tvgId)) {
                byTvg.putIfAbsent(key, target)
            }
        }
        return DaddyIndexes(
            byCoreName = byCore,
            byTvg = byTvg,
            byLegacyNorm = byLegacy,
            all = all,
        )
    }

    fun resolveDaddyChannelId(
        name: String,
        tvgId: String?,
        indexes: DaddyIndexes,
        tags: List<String> = emptyList(),
        countryHint: String? = null,
        sourcePlaylist: String? = null,
        minScore: Int = SupplementMatchScorer.MIN_SCORE,
    ): String? =
        SupplementMatchScorer.bestMatch(
            candidateName = name,
            candidateTvgId = tvgId,
            indexes = indexes,
            candidateTags = tags,
            candidateCountryHint = countryHint,
            candidateSourcePlaylist = sourcePlaylist,
            minScore = minScore,
        )?.daddyChannelId

    fun matchesDaddy(
        name: String,
        tvgId: String?,
        indexes: DaddyIndexes,
        tags: List<String> = emptyList(),
        countryHint: String? = null,
        sourcePlaylist: String? = null,
        minScore: Int = SupplementMatchScorer.MIN_SCORE,
    ): Boolean =
        resolveDaddyChannelId(
            name = name,
            tvgId = tvgId,
            indexes = indexes,
            tags = tags,
            countryHint = countryHint,
            sourcePlaylist = sourcePlaylist,
            minScore = minScore,
        ) != null
}
