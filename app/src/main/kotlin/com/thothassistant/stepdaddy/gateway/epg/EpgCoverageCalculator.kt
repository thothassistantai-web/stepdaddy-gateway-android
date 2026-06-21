package com.thothassistant.stepdaddy.gateway.epg

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.EpgCoverage
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource

object EpgCoverageCalculator {
    fun snapshot(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
        meta: EpgMeta,
    ): EpgCoverage {
        val playlistTotal = channels.size + supplements.size
        val daddyWithTvg = channels.count { !it.tvgId.isNullOrBlank() }
        val suppWithTvg = supplements.count { !it.tvgId.isNullOrBlank() }
        val withTvgId = daddyWithTvg + suppWithTvg
        val supplementNoTvgId = supplements.count { it.tvgId.isNullOrBlank() }
        val mappedPercent = if (playlistTotal > 0) withTvgId * 100f / playlistTotal else 0f
        val withProgrammes = meta.channelsWithProgrammes.takeIf { it > 0 }
            ?: meta.channelCount
        val programmePercent = if (withTvgId > 0) {
            meta.channelsWithRealProgrammes.takeIf { it > 0 }?.times(100f)?.div(withTvgId)
                ?: meta.programmeCount.takeIf { it > 0 }?.let { withProgrammes * 100f / withTvgId }
                ?: 0f
        } else {
            0f
        }
        return EpgCoverage(
            playlistChannels = playlistTotal,
            withTvgId = withTvgId,
            withProgrammes = withProgrammes,
            withRealProgrammes = meta.channelsWithRealProgrammes,
            withPlaceholders = meta.channelsWithPlaceholders,
            unmapped = playlistTotal - withTvgId,
            supplementNoTvgId = supplementNoTvgId,
            mappedPercent = mappedPercent,
            programmePercent = programmePercent ?: 0f,
            placeholderProgrammes = meta.placeholderProgrammeCount,
        )
    }

    fun snapshot(
        channels: List<Channel>,
        supplementSource: SupplementSource?,
        meta: EpgMeta,
    ): EpgCoverage = snapshot(channels, supplementSource?.channels().orEmpty(), meta)
}
