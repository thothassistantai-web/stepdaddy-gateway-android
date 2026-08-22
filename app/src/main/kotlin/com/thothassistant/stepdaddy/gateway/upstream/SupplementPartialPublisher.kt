package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

/**
 * Progressive mid-sync catalog assembly: merge ready provider pieces and retain previous
 * cached rows for prefixes still loading.
 */
internal object SupplementPartialPublisher {
    fun mergePieces(
        previousCached: List<SupplementChannel>,
        specialEvents: List<SupplementChannel>?,
        iptvOrg: List<SupplementChannel>?,
        freeTv: List<SupplementChannel>?,
        duloCx: List<SupplementChannel>?,
        ntvCx: List<SupplementChannel>?,
        adultSwim: List<SupplementChannel>?,
        tmdbVod: List<SupplementChannel>?,
        tmdbVodSeries: List<SupplementChannel>?,
    ): List<SupplementChannel> {
        fun piece(
            ready: List<SupplementChannel>?,
            retain: (SupplementChannel) -> Boolean,
        ): List<SupplementChannel> =
            when {
                ready != null -> ready
                else -> previousCached.filter(retain)
            }

        return piece(specialEvents) { EventLifecycleManager.isSpecialEventChannel(it.id) } +
            piece(iptvOrg) { it.id.startsWith("iptv:") } +
            piece(freeTv) { it.id.startsWith(FreeTvIptvConfig.ID_PREFIX) } +
            piece(duloCx) { it.id.startsWith(DuloCxLiveConfig.ID_PREFIX) } +
            piece(ntvCx) { it.id.startsWith("ntv:") } +
            piece(adultSwim) { it.id.startsWith("adultswim:") } +
            piece(tmdbVod) { it.id.startsWith(TmdbVodConfig.ID_PREFIX) } +
            piece(tmdbVodSeries) { it.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) }
    }

    fun mergeDaddyFallbacks(
        daddyFallbackMaps: List<Map<String, List<SupplementFallbackMirror>>>,
        consolidationOverrides: ConsolidationOverrideStore,
    ): Map<String, List<SupplementFallbackMirror>> {
        if (daddyFallbackMaps.isEmpty()) return emptyMap()
        return SupplementFallbackOverridesApplier.apply(
            SupplementImportHelper.mergeDaddyFallbackMaps(*daddyFallbackMaps.toTypedArray()),
            consolidationOverrides.current(),
        )
    }
}
