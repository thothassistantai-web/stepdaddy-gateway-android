package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TiviMateEvent(
    val event: String,
    val channelNo: Int? = null,
    val channelName: String? = null,
    val timestamp: Long = 0L,
    val message: String? = null,
    val wizardPhase: String? = null,
    val channelId: Long? = null,
    val setupDone: Boolean? = null,
    val patchVersion: String? = null,
)

/** Patch POST body (`type` / `detail`) normalized to [TiviMateEvent] (`event` / `message`). */
@Serializable
internal data class TiviMateEventIngest(
    val event: String? = null,
    val type: String? = null,
    val channelNo: Int? = null,
    val channelName: String? = null,
    val timestamp: Long = 0L,
    val message: String? = null,
    val detail: String? = null,
    val wizardPhase: String? = null,
    val channelId: Long? = null,
    val setupDone: Boolean? = null,
    val patchVersion: String? = null,
) {
    fun toEvent(): TiviMateEvent = TiviMateEvent(
        event = (event ?: type).orEmpty(),
        channelNo = channelNo,
        channelName = channelName,
        timestamp = timestamp,
        message = message ?: detail,
        wizardPhase = wizardPhase,
        channelId = channelId,
        setupDone = setupDone,
        patchVersion = patchVersion,
    )
}

@Serializable
data class TiviMateEventIngestResponse(
    val ok: Boolean = true,
    val buffered: Int = 0,
)

@Serializable
data class TiviMateEventsResponse(
    val events: List<TiviMateEvent> = emptyList(),
    val count: Int = 0,
    val since: Long? = null,
)

@Serializable
data class TiviMateHandshake(
    val deviceId: String,
    val gatewayVersion: String,
    val bootChannel: Int? = null,
    val features: List<String> = listOf("events", "state"),
    val gatewayBase: String = "",
    val eventsUrl: String = "",
    val stateUrl: String = "",
)

/** Loopback `GET /status` body from patched TiViMate (`:4617`). */
@Serializable
data class TiviMateHttpStatus(
    val ok: Boolean? = null,
    val patchVersion: String? = null,
    @SerialName("package") val packageName: String? = null,
    val setupDone: Boolean? = null,
    val hasPlaylist: Boolean? = null,
    val playlistCount: Int? = null,
    val channelCount: Int? = null,
    val wizardPending: Boolean? = null,
    val wizardPhase: String? = null,
    val stateReason: String? = null,
    val playlistName: String? = null,
    val playlistUrl: String? = null,
    val gatewayBase: String? = null,
    val port: Int? = null,
)

@Serializable
data class TiviMatePlayerState(
    val ok: Boolean? = null,
    val setupDone: Boolean? = null,
    val wizardPending: Boolean? = null,
    val wizardPhase: String? = null,
    val hasPlaylist: Boolean? = null,
    val stateReason: String? = null,
    val playlistName: String? = null,
    val playlistUrl: String? = null,
    val currentChannelId: Long? = null,
    val currentChannelNo: Int? = null,
    val currentChannelName: String? = null,
    val isPlaying: Boolean? = null,
    val playerMode: String? = null,
    val playlistCount: Int? = null,
    val channelCount: Int? = null,
    val gatewayBase: String? = null,
    val patchVersion: String? = null,
)

@Serializable
data class TiviMateStateResponse(
    val reachable: Boolean = false,
    val statusCode: Int? = null,
    val state: TiviMatePlayerState? = null,
    val error: String? = null,
)

@Serializable
data class TiviMateChannelRow(
    val id: Long = 0L,
    @SerialName("tvg_ch_no") val channelNo: Int? = null,
    val name: String = "",
)

@Serializable
internal data class TiviMateChannelsPayload(
    val ok: Boolean = false,
    val channels: List<TiviMateChannelRow> = emptyList(),
    val error: String? = null,
)

@Serializable
data class TiviMateChannelsResponse(
    val reachable: Boolean = false,
    val channels: List<TiviMateChannelRow> = emptyList(),
    val error: String? = null,
)

@Serializable
data class TiviMateHealthEvents(
    val buffered: Int = 0,
    val lastEvent: String? = null,
    val lastTimestamp: Long? = null,
)
