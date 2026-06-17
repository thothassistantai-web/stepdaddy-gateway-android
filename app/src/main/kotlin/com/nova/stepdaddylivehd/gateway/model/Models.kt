package com.nova.stepdaddylivehd.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpstreamChannelRow(
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("channel_name") val channelName: String = "",
)

data class Channel(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val logo: String? = null,
    val tvgId: String? = null,
)

data class UpstreamManifest(
    val playlistText: String,
    val masterUrl: String,
    val refererHost: String,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val channels: Int,
    val port: Int,
    val baseUrl: String,
    val upstreamBaseUrl: String,
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
)

@Serializable
data class TivimateSetup(
    val playlist: String,
    val epg: String,
    val health: String,
    val hint: String,
    val epgReady: Boolean = false,
    val epgProgrammeCount: Int = 0,
    val epgAgeSeconds: Long? = null,
)
