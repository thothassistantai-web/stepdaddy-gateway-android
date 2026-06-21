package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class AdminDiscovery(
    val version: String,
    val api: String = "stepdaddy-gateway-admin/v1",
    val baseUrl: String,
    val endpoints: List<AdminEndpoint> = emptyList(),
)

@Serializable
data class AdminEndpoint(
    val method: String,
    val path: String,
    val description: String,
)

@Serializable
data class AdminActionResult(
    val ok: Boolean,
    val action: String,
    val message: String = "",
    val channels: Int? = null,
    val supplements: Int? = null,
    val assigned: Int? = null,
    val scanned: Int? = null,
    val requiresRestart: Boolean = false,
)

@Serializable
data class AdminChannelSummary(
    val id: String,
    val name: String,
    val groupTitle: String,
    val countryCode: String,
    val tvgId: String? = null,
    val source: String,
)

@Serializable
data class AdminChannelSearchResult(
    val query: String,
    val count: Int,
    val channels: List<AdminChannelSummary>,
)

@Serializable
data class AdminSettingsSnapshot(
    val port: Int,
    val networkAccessMode: String,
    val gatewayName: String,
    val dlhdBaseUrl: String,
    val mirrorUrls: List<String>,
    val embeddedSidecarEnabled: Boolean,
    val supplementBaseUrl: String,
    val supplementSportsEnabled: Boolean,
    val supplementIptvOrgEnabled: Boolean,
    val supplementNtvCxEnabled: Boolean,
    val supplementAdultSwimEnabled: Boolean,
    val iptvOrgEpgEnabled: Boolean,
    val iptvOrgEpgUrl: String,
    val startOnBoot: Boolean,
    val autoStartOnLaunch: Boolean,
)

@Serializable
data class AdminSettingsPatch(
    val port: Int? = null,
    val networkAccessMode: String? = null,
    val gatewayName: String? = null,
    val dlhdBaseUrl: String? = null,
    val mirrorUrls: List<String>? = null,
    val embeddedSidecarEnabled: Boolean? = null,
    val supplementBaseUrl: String? = null,
    val supplementSportsEnabled: Boolean? = null,
    val supplementIptvOrgEnabled: Boolean? = null,
    val supplementNtvCxEnabled: Boolean? = null,
    val supplementAdultSwimEnabled: Boolean? = null,
    val iptvOrgEpgEnabled: Boolean? = null,
    val iptvOrgEpgUrl: String? = null,
    val startOnBoot: Boolean? = null,
    val autoStartOnLaunch: Boolean? = null,
)

@Serializable
data class LogoOverrideRequest(
    val channelName: String,
    val url: String,
)

@Serializable
data class EpgNameOverrideRequest(
    val channelName: String,
    val tvgId: String,
)

@Serializable
data class EpgIdOverrideRequest(
    val channelId: String,
    val tvgId: String,
)

@Serializable
data class ResolveLogoResult(
    val channelName: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val resolved: Boolean,
)

@Serializable
data class ResolveEpgResult(
    val channelName: String,
    val tvgId: String? = null,
    val resolved: Boolean,
)

@Serializable
data class AdminSettingsPatchResult(
    val ok: Boolean = true,
    val action: String = "patch-settings",
    val message: String = "",
    val requiresRestart: Boolean = false,
    val settings: AdminSettingsSnapshot,
)

@Serializable
data class AdminErrorResponse(
    val error: String,
    val message: String,
)
