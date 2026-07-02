package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import kotlinx.serialization.Serializable

@Serializable
data class DaddyChannelFallbackCache(
    val fallbacks: Map<String, List<SupplementFallbackMirror>> = emptyMap(),
    val syncedAtMs: Long = 0L,
)
