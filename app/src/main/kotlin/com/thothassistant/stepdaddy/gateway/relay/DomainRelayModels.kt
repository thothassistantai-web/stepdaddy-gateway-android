package com.thothassistant.stepdaddy.gateway.relay

import kotlinx.serialization.Serializable

@Serializable
data class DomainRelayManifest(
    val version: Int,
    val minAppVersion: String? = null,
    val forceUpdateAfter: String? = null,
    val message: String? = null,
    val sources: Map<String, DomainRelaySource> = emptyMap(),
) {
    fun daddylive(): DomainRelaySource? = sources["daddylive"]
}

@Serializable
data class DomainRelaySource(
    val primary: String? = null,
    val mirrors: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val relayHosts: List<String> = emptyList(),
    val embedHosts: List<String> = emptyList(),
)

data class DomainRelayStatus(
    val active: Boolean = false,
    val version: Int = 0,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val message: String = "",
    val forceUpdateDue: Boolean = false,
    val primary: String = "",
    val mirrorCount: Int = 0,
)

@Serializable
data class DomainRelayHealth(
    val active: Boolean = false,
    val version: Int = 0,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val forceUpdateDue: Boolean = false,
    val primary: String = "",
    val mirrorCount: Int = 0,
)
