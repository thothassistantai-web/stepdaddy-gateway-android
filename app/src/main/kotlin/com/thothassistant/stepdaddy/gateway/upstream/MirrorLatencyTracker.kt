package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.ConcurrentHashMap

/**
 * Exponential moving average latency per mirror host and per dlhd.pk relay path.
 * Lower EMA = faster mirror/path on subsequent resolves.
 */
class MirrorLatencyTracker(
    private val alpha: Double = GatewayConfig.MIRROR_LATENCY_EMA_ALPHA,
    private val unknownLatencyMs: Double = GatewayConfig.MIRROR_UNKNOWN_LATENCY_MS,
    private val failurePenaltyMs: Long = GatewayConfig.MIRROR_FAILURE_PENALTY_MS,
) {
    private val mirrorLatencyEmaMs = ConcurrentHashMap<String, Double>()
    private val dlhdPathLatencyEmaMs = ConcurrentHashMap<String, Double>()

    fun recordMirrorSuccess(baseUrl: String, latencyMs: Long) {
        updateEma(mirrorLatencyEmaMs, baseUrl.trimEnd('/'), latencyMs.toDouble())
    }

    fun recordMirrorFailure(baseUrl: String) {
        updateEma(mirrorLatencyEmaMs, baseUrl.trimEnd('/'), failurePenaltyMs.toDouble())
    }

    fun recordDlhdPathSuccess(path: String, latencyMs: Long) {
        updateEma(dlhdPathLatencyEmaMs, path, latencyMs.toDouble())
    }

    fun recordDlhdPathFailure(path: String) {
        updateEma(dlhdPathLatencyEmaMs, path, failurePenaltyMs.toDouble())
    }

    fun mirrorLatencyMs(baseUrl: String): Double? = mirrorLatencyEmaMs[baseUrl.trimEnd('/')]

    fun dlhdPathLatencyMs(path: String): Double? = dlhdPathLatencyEmaMs[path]

    fun fastestMirrorEmaMs(): Double? = mirrorLatencyEmaMs.values.minOrNull()

    fun mirrorLatencySnapshot(): Map<String, Double> = mirrorLatencyEmaMs.toMap()

    fun orderedDlhdPaths(paths: List<String>): List<String> =
        paths.sortedWith(
            compareBy<String> { dlhdPathLatencyEmaMs[it] ?: unknownLatencyMs }
                .thenBy { paths.indexOf(it) },
        )

    companion object {
        fun orderedMirrorUrls(
            activeBaseUrl: String,
            dlhdBaseUrl: String,
            configuredMirrors: List<String>,
            mirrorLatencyMs: (String) -> Double?,
            unknownLatencyMs: Double = GatewayConfig.MIRROR_UNKNOWN_LATENCY_MS,
            isExcluded: (String) -> Boolean = { false },
        ): List<String> {
            val configured = linkedSetOf<String>()
            configured += dlhdBaseUrl.trimEnd('/')
            configured += configuredMirrors.map { it.trimEnd('/') }
            val active = activeBaseUrl.trimEnd('/')
            val rest = configured
                .filter { it.isNotBlank() && it != active && !isExcluded(it) }
                .sortedWith(
                    compareBy<String> { mirrorLatencyMs(it) ?: unknownLatencyMs }
                        .thenBy { it },
                )
            return buildList {
                if (active.isNotBlank() && !isExcluded(active)) {
                    add(active)
                }
                rest.forEach { url ->
                    if (url !in this) add(url)
                }
            }
        }

        private fun normalizeKey(baseUrl: String): String = baseUrl.trimEnd('/')
    }

    private fun updateEma(store: ConcurrentHashMap<String, Double>, key: String, sampleMs: Double) {
        store.compute(key) { _, previous ->
            if (previous == null) {
                sampleMs
            } else {
                alpha * sampleMs + (1.0 - alpha) * previous
            }
        }
    }
}
