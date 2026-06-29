package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.util.concurrent.ConcurrentHashMap

class DlhdEventStreamHealthStore {
    private val entries = ConcurrentHashMap<String, DlhdEventStreamHealth.Entry>()

    @Volatile
    private var revision = 0L

    fun revision(): Long = revision

    fun status(token: String): DlhdEventStreamHealth.Status =
        entries[token.trim()]?.status ?: DlhdEventStreamHealth.Status.UNKNOWN

    fun statusForSupplement(supplement: SupplementChannel): DlhdEventStreamHealth.Status {
        if (!supplement.id.startsWith("dlhd-event:")) return DlhdEventStreamHealth.Status.UNKNOWN
        return status(supplement.id.removePrefix("dlhd-event:"))
    }

    fun isHealthy(token: String): Boolean =
        status(token) == DlhdEventStreamHealth.Status.HEALTHY

    fun entry(token: String): DlhdEventStreamHealth.Entry? = entries[token.trim()]

    fun record(token: String, result: DlhdEventStreamHealth.ProbeResult) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val previous = entries[trimmed]?.status
        entries[trimmed] = DlhdEventStreamHealth.Entry(
            status = result.status,
            lastProbeMs = System.currentTimeMillis(),
            lastError = result.error,
        )
        if (previous != result.status) {
            revision++
        }
    }

    fun pruneTokens(activeTokens: Collection<String>) {
        val active = activeTokens.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val removed = entries.keys.retainAll(active)
        if (removed) {
            revision++
        }
    }

    fun summary(activeStreams: Int): DlhdEventStreamHealth.Summary {
        val values = entries.values
        val probed = values.size
        val healthy = values.count { it.status == DlhdEventStreamHealth.Status.HEALTHY }
        val unhealthy = values.count { it.status == DlhdEventStreamHealth.Status.UNHEALTHY }
        val ended = values.count { it.status == DlhdEventStreamHealth.Status.ENDED }
        val unknown = (activeStreams - probed).coerceAtLeast(0) +
            values.count { it.status == DlhdEventStreamHealth.Status.UNKNOWN }
        return DlhdEventStreamHealth.Summary(
            activeStreams = activeStreams,
            probed = probed,
            healthy = healthy,
            unhealthy = unhealthy,
            ended = ended,
            unknown = unknown,
            lastProbeMs = values.maxOfOrNull { it.lastProbeMs },
        )
    }

    fun snapshot(): Map<String, DlhdEventStreamHealth.Entry> = entries.toMap()
}
