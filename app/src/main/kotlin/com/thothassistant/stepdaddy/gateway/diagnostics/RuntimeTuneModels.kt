package com.thothassistant.stepdaddy.gateway.diagnostics

import kotlinx.serialization.Serializable

/**
 * Allowlisted runtime performance/stream knobs. Applied without APK rebuild.
 * Unknown keys are rejected; values are clamped server-side.
 */
@Serializable
data class RuntimeTunePack(
    val version: Int = 0,
    val minAppVersion: String? = null,
    val message: String = "",
    val tune: RuntimeTuneValues = RuntimeTuneValues(),
)

@Serializable
data class RuntimeTuneValues(
    val contentProxyMaxConcurrent: Int? = null,
    val contentProxyWaitMs: Long? = null,
    val upstreamFetchMaxConcurrent: Int? = null,
    val upstreamFetchWaitMs: Long? = null,
    val streamFetchTimeoutMs: Long? = null,
    val mirrorAttemptTimeoutMs: Long? = null,
    val dlhdRaceTimeoutMs: Long? = null,
    val streamCacheTtlMs: Long? = null,
    val livePlaylistCacheTtlMs: Long? = null,
    val upstreamCacheTtlMs: Long? = null,
    val dlhdPkParallelProbeCount: Int? = null,
    val hedgedMirrorRaceEnabled: Boolean? = null,
)

@Serializable
data class RuntimeTuneEffective(
    val source: String = "defaults",
    val packVersion: Int = 0,
    val message: String = "",
    val fetchedAtMs: Long = 0L,
    val contentProxyMaxConcurrent: Int,
    val contentProxyWaitMs: Long,
    val upstreamFetchMaxConcurrent: Int,
    val upstreamFetchWaitMs: Long,
    val streamFetchTimeoutMs: Long,
    val mirrorAttemptTimeoutMs: Long,
    val dlhdRaceTimeoutMs: Long,
    val streamCacheTtlMs: Long,
    val livePlaylistCacheTtlMs: Long,
    val upstreamCacheTtlMs: Long,
    val dlhdPkParallelProbeCount: Int,
    val hedgedMirrorRaceEnabled: Boolean,
)

@Serializable
data class RuntimeTuneStatus(
    val active: Boolean = false,
    val version: Int = 0,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val message: String = "",
)

@Serializable
data class DebugDiagnosticsResponse(
    val ok: Boolean = true,
    val version: String = "",
    val memory: DebugMemorySnapshot = DebugMemorySnapshot(),
    val tune: RuntimeTuneEffective,
    val tuneStatus: RuntimeTuneStatus = RuntimeTuneStatus(),
    val streams: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val proxyInFlight: Int = 0,
    val upstreamInFlight: Int = 0,
)

@Serializable
data class DebugMemorySnapshot(
    val maxMb: Long = 0,
    val totalMb: Long = 0,
    val freeMb: Long = 0,
    val usedMb: Long = 0,
)

@Serializable
data class DebugProbeResult(
    val channelId: String,
    val manifestHttp: Int = 0,
    val manifestMs: Long = 0,
    val segmentHttp: Int = 0,
    val segmentMs: Long = 0,
    val segmentBytes: Int = 0,
    val contentType: String = "",
    val tsMagic: Boolean = false,
    val ok: Boolean = false,
    val detail: String = "",
)
