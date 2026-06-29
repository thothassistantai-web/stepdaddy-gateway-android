package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.DlhdEventMirror

/**
 * Ranks DaddyLive event mirrors for playlist emission and play-time failover.
 * Hot mirrors are probed eagerly; cold mirrors are tried lazily on primary failure.
 */
object SpecialEventMirrorRanker {
  data class RankedMirrors(
      val hot: List<DlhdEventMirror>,
      val cold: List<DlhdEventMirror>,
  )

  fun rankMirrors(
      mirrors: List<DlhdEventMirror>,
      hotLimit: Int = SupplementConfig.HOT_MIRRORS_PER_EVENT,
  ): RankedMirrors {
      if (mirrors.isEmpty()) return RankedMirrors(emptyList(), emptyList())
      val sorted = mirrors
          .distinctBy { it.streamKey.lowercase() }
          .sortedWith(
              compareByDescending<DlhdEventMirror> { scoreMirror(it) }
                  .thenBy { upstreamOrder(it.streamKey) },
          )
      val limit = hotLimit.coerceAtLeast(1)
      val hot = sorted.take(limit)
      val cold = sorted.drop(limit)
      return RankedMirrors(hot = hot, cold = cold)
  }

  fun orderedMirrors(
      mirrors: List<DlhdEventMirror>,
      hotLimit: Int = SupplementConfig.HOT_MIRRORS_PER_EVENT,
  ): List<DlhdEventMirror> {
      val ranked = rankMirrors(mirrors, hotLimit)
      return ranked.hot + ranked.cold
  }

  fun scoreMirror(mirror: DlhdEventMirror, upstreamIndex: Int = 0): Int {
      var score = 1000 - upstreamIndex.coerceAtLeast(0) * 5
      val key = mirror.streamKey.lowercase()
      when {
          key.startsWith("tv|") -> score += 40
          key.startsWith("tv2|") -> score += 20
      }
      score += mirror.probeScore
      when (mirror.healthy) {
          true -> score += 80
          false -> score -= 250
          null -> Unit
      }
      val label = mirror.label.trim().lowercase()
      if (label.isNotEmpty() && !label.startsWith("link")) {
          score += 5
      }
      return score
  }

  fun selectFailoverIndex(
      mirrors: List<DlhdEventMirror>,
      failedIndex: Int,
  ): Int? {
      if (mirrors.isEmpty()) return null
      val start = (failedIndex + 1).coerceAtLeast(0)
      for (index in start until mirrors.size) {
          if (mirrors[index].healthy != false) return index
      }
      for (index in 0 until start.coerceAtMost(mirrors.size)) {
          if (mirrors[index].healthy != false) return index
      }
      return null
  }

  private fun upstreamOrder(streamKey: String): Int {
      val key = streamKey.lowercase()
      return when {
          key.startsWith("tv|") -> 0
          key.startsWith("tv2|") -> 1
          else -> 2
      }
  }
}
