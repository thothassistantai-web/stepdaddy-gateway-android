package com.thothassistant.stepdaddy.gateway.diagnostics

import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig

/**
 * Process-wide allowlisted tune overlays (debug API and/or silent pack pull).
 */
object RuntimeTuneRuntime {
    @Volatile
    private var applied: AppliedTune? = null

    /** Shared limiters so /debug can report in-flight counts. */
    val contentProxyLimiter = DynamicSlotLimiter()
    val upstreamFetchLimiter = DynamicSlotLimiter()

    val isActive: Boolean
        get() = applied != null

    val version: Int
        get() = applied?.pack?.version ?: 0

    val sourceLabel: String
        get() = applied?.sourceLabel.orEmpty()

    val fetchedAtMs: Long
        get() = applied?.fetchedAtMs ?: 0L

    val message: String
        get() = applied?.pack?.message.orEmpty()

    fun apply(pack: RuntimeTunePack, sourceLabel: String, fetchedAtMs: Long = System.currentTimeMillis()) {
        applied = AppliedTune(sanitize(pack), sourceLabel, fetchedAtMs)
    }

    fun clear() {
        applied = null
    }

    fun status(): RuntimeTuneStatus {
        val current = applied ?: return RuntimeTuneStatus()
        return RuntimeTuneStatus(
            active = true,
            version = current.pack.version,
            source = current.sourceLabel,
            fetchedAtMs = current.fetchedAtMs,
            message = current.pack.message,
        )
    }

    fun effective(): RuntimeTuneEffective {
        val t = applied?.pack?.tune
        return RuntimeTuneEffective(
            source = applied?.sourceLabel ?: "defaults",
            packVersion = applied?.pack?.version ?: 0,
            message = applied?.pack?.message.orEmpty(),
            fetchedAtMs = applied?.fetchedAtMs ?: 0L,
            contentProxyMaxConcurrent = t?.contentProxyMaxConcurrent
                ?: GatewayConfig.DEFAULT_CONTENT_PROXY_MAX_CONCURRENT,
            contentProxyWaitMs = t?.contentProxyWaitMs
                ?: GatewayConfig.DEFAULT_CONTENT_PROXY_WAIT_MS,
            upstreamFetchMaxConcurrent = t?.upstreamFetchMaxConcurrent
                ?: GatewayConfig.DEFAULT_UPSTREAM_FETCH_MAX_CONCURRENT,
            upstreamFetchWaitMs = t?.upstreamFetchWaitMs
                ?: GatewayConfig.DEFAULT_UPSTREAM_FETCH_WAIT_MS,
            streamFetchTimeoutMs = t?.streamFetchTimeoutMs
                ?: GatewayConfig.DEFAULT_STREAM_FETCH_TIMEOUT_MS,
            mirrorAttemptTimeoutMs = t?.mirrorAttemptTimeoutMs
                ?: GatewayConfig.DEFAULT_MIRROR_ATTEMPT_TIMEOUT_MS,
            dlhdRaceTimeoutMs = t?.dlhdRaceTimeoutMs
                ?: GatewayConfig.DEFAULT_DLHD_RACE_TIMEOUT_MS,
            streamCacheTtlMs = t?.streamCacheTtlMs
                ?: GatewayConfig.DEFAULT_STREAM_CACHE_TTL_MS,
            livePlaylistCacheTtlMs = t?.livePlaylistCacheTtlMs
                ?: GatewayConfig.DEFAULT_LIVE_PLAYLIST_CACHE_TTL_MS,
            upstreamCacheTtlMs = t?.upstreamCacheTtlMs
                ?: GatewayConfig.DEFAULT_UPSTREAM_CACHE_TTL_MS,
            dlhdPkParallelProbeCount = t?.dlhdPkParallelProbeCount
                ?: GatewayConfig.DEFAULT_DLHD_PK_PARALLEL_PROBE_COUNT,
            hedgedMirrorRaceEnabled = t?.hedgedMirrorRaceEnabled
                ?: GatewayConfig.DEFAULT_HEDGED_MIRROR_RACE_ENABLED,
        )
    }

    /** Merge patch over current effective values and apply as a local debug pack. */
    fun applyPatch(patch: RuntimeTuneValues, sourceLabel: String = "debug-api"): RuntimeTuneEffective {
        val cur = applied?.pack?.tune ?: RuntimeTuneValues()
        val merged = RuntimeTuneValues(
            contentProxyMaxConcurrent = patch.contentProxyMaxConcurrent ?: cur.contentProxyMaxConcurrent,
            contentProxyWaitMs = patch.contentProxyWaitMs ?: cur.contentProxyWaitMs,
            upstreamFetchMaxConcurrent = patch.upstreamFetchMaxConcurrent ?: cur.upstreamFetchMaxConcurrent,
            upstreamFetchWaitMs = patch.upstreamFetchWaitMs ?: cur.upstreamFetchWaitMs,
            streamFetchTimeoutMs = patch.streamFetchTimeoutMs ?: cur.streamFetchTimeoutMs,
            mirrorAttemptTimeoutMs = patch.mirrorAttemptTimeoutMs ?: cur.mirrorAttemptTimeoutMs,
            dlhdRaceTimeoutMs = patch.dlhdRaceTimeoutMs ?: cur.dlhdRaceTimeoutMs,
            streamCacheTtlMs = patch.streamCacheTtlMs ?: cur.streamCacheTtlMs,
            livePlaylistCacheTtlMs = patch.livePlaylistCacheTtlMs ?: cur.livePlaylistCacheTtlMs,
            upstreamCacheTtlMs = patch.upstreamCacheTtlMs ?: cur.upstreamCacheTtlMs,
            dlhdPkParallelProbeCount = patch.dlhdPkParallelProbeCount ?: cur.dlhdPkParallelProbeCount,
            hedgedMirrorRaceEnabled = patch.hedgedMirrorRaceEnabled ?: cur.hedgedMirrorRaceEnabled,
        )
        val nextVersion = (applied?.pack?.version ?: 0) + 1
        apply(
            RuntimeTunePack(
                version = nextVersion,
                message = "Local debug tune",
                tune = merged,
            ),
            sourceLabel = sourceLabel,
        )
        return effective()
    }

    private fun sanitize(pack: RuntimeTunePack): RuntimeTunePack {
        val t = pack.tune
        return pack.copy(
            tune = RuntimeTuneValues(
                contentProxyMaxConcurrent = t.contentProxyMaxConcurrent?.coerceIn(1, 8),
                contentProxyWaitMs = t.contentProxyWaitMs?.coerceIn(1_000L, 60_000L),
                upstreamFetchMaxConcurrent = t.upstreamFetchMaxConcurrent?.coerceIn(1, 8),
                upstreamFetchWaitMs = t.upstreamFetchWaitMs?.coerceIn(1_000L, 60_000L),
                streamFetchTimeoutMs = t.streamFetchTimeoutMs?.coerceIn(5_000L, 90_000L),
                mirrorAttemptTimeoutMs = t.mirrorAttemptTimeoutMs?.coerceIn(2_000L, 45_000L),
                dlhdRaceTimeoutMs = t.dlhdRaceTimeoutMs?.coerceIn(3_000L, 60_000L),
                streamCacheTtlMs = t.streamCacheTtlMs?.coerceIn(1_000L, 600_000L),
                livePlaylistCacheTtlMs = t.livePlaylistCacheTtlMs?.coerceIn(500L, 30_000L),
                upstreamCacheTtlMs = t.upstreamCacheTtlMs?.coerceIn(1_000L, 600_000L),
                dlhdPkParallelProbeCount = t.dlhdPkParallelProbeCount?.coerceIn(1, 5),
                hedgedMirrorRaceEnabled = t.hedgedMirrorRaceEnabled,
            ),
        )
    }

    private data class AppliedTune(
        val pack: RuntimeTunePack,
        val sourceLabel: String,
        val fetchedAtMs: Long,
    )
}
