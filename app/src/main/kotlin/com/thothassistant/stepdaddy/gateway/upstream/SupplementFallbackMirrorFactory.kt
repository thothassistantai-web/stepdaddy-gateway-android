package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

object SupplementFallbackMirrorFactory {
    fun fromSupplement(channel: SupplementChannel): SupplementFallbackMirror =
        SupplementFallbackMirror(
            streamUrl = channel.streamUrl.trim(),
            label = channel.providerTag?.takeIf { it.isNotBlank() }
                ?: channel.id.substringBefore(':').ifBlank { "supplement" },
            referer = channel.referer,
            origin = channel.origin,
            ntvCdnLiveKey = channel.ntvCdnLiveKey,
            duloChannelId = channel.duloChannelId,
        )

    fun countryLabel(channel: SupplementChannel): String {
        val fromTags = SupplementMatchScorer.extractSignals(
            name = channel.name,
            tvgId = channel.tvgId,
            tags = channel.tags,
            countryHint = channel.regionCode,
        ).region
        return fromTags ?: channel.regionCode?.uppercase().orEmpty()
    }

    fun suggestMatches(
        daddy: Channel,
        supplements: List<SupplementChannel>,
        alreadyAttached: List<SupplementFallbackMirror>,
        denylist: Set<String>,
        limit: Int = 8,
    ): List<SuggestedBackup> {
        val attachedFp = alreadyAttached.map { SupplementMatchScorer.mirrorFingerprint(it) }.toSet()
        val indexes = SupplementImportMatcher.buildDaddyIndexes(listOf(daddy))
        return supplements.asSequence()
            .mapNotNull { supplement ->
                val mirror = fromSupplement(supplement)
                val fp = SupplementMatchScorer.mirrorFingerprint(mirror)
                if (fp in attachedFp) return@mapNotNull null
                val pair = SupplementMatchScorer.pairKey(daddy.id, fp)
                if (pair in denylist) return@mapNotNull null
                val scored = SupplementMatchScorer.bestMatch(
                    candidateName = supplement.name,
                    candidateTvgId = supplement.tvgId,
                    indexes = indexes,
                    candidateTags = supplement.tags,
                    candidateCountryHint = supplement.regionCode,
                    minScore = 55, // surface near-misses for Accept/Reject
                ) ?: return@mapNotNull null
                if (scored.daddyChannelId != daddy.id) return@mapNotNull null
                SuggestedBackup(
                    supplement = supplement,
                    mirror = mirror,
                    score = scored.score,
                    reasons = scored.reasons,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }
}

data class SuggestedBackup(
    val supplement: SupplementChannel,
    val mirror: SupplementFallbackMirror,
    val score: Int,
    val reasons: List<String>,
)
