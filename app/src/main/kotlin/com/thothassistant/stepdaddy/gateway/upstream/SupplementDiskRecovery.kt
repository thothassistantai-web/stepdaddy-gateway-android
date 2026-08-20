package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror

/** Rebuild supplement rows from on-disk NTV catalog when channels.json is empty. */
internal object SupplementDiskRecovery {
    data class Result(
        val channels: List<SupplementChannel>,
        val daddyFallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    )

    fun recoverNtvFromCatalog(
        enabled: Boolean,
        ntvCxEnabled: Boolean,
        catalogStore: NtvCxCatalogStore,
        daddyChannels: List<Channel>,
        environment: GatewayEnvironment,
    ): Result {
        if (!enabled || !ntvCxEnabled) return Result(emptyList())
        val catalog = catalogStore.loadCatalog()
        if (catalog.isEmpty()) return Result(emptyList())
        val built = NtvCxCdnLiveSource.buildChannels(
            catalog = catalog,
            daddyChannels = daddyChannels,
            mergeMode = environment.supplementNtvCxImportMode,
            nameIndex = null,
        )
        return Result(channels = built.channels, daddyFallbacks = built.daddyFallbacks)
    }
}
