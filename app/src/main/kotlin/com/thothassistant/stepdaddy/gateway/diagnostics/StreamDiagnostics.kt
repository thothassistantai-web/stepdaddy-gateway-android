package com.thothassistant.stepdaddy.gateway.diagnostics

import kotlinx.serialization.Serializable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide stream telemetry for live diagnostics (no rebuild required to inspect).
 */
object StreamDiagnostics {
    private const val RECENT_MAX = 40
    private val lock = Any()

    private val resolveOk = AtomicLong(0)
    private val resolveFail = AtomicLong(0)
    private val resolve504 = AtomicLong(0)
    private val resolve502 = AtomicLong(0)
    private val resolve503 = AtomicLong(0)
    private val segmentOk = AtomicLong(0)
    private val segmentFail = AtomicLong(0)
    private val unwrapHits = AtomicLong(0)
    private val proxyBusy = AtomicLong(0)
    private val resolveLatencySumMs = AtomicLong(0)
    private val resolveLatencyCount = AtomicLong(0)
    private val segmentLatencySumMs = AtomicLong(0)
    private val segmentLatencyCount = AtomicLong(0)

    private val recent = ArrayDeque<DiagnosticsEvent>(RECENT_MAX)
    private val lastByChannel = linkedMapOf<String, DiagnosticsEvent>()

    fun recordResolve(
        channelId: String,
        httpStatus: Int,
        latencyMs: Long,
        ok: Boolean,
        detail: String = "",
    ) {
        if (ok) {
            resolveOk.incrementAndGet()
        } else {
            resolveFail.incrementAndGet()
            when (httpStatus) {
                504 -> resolve504.incrementAndGet()
                502 -> resolve502.incrementAndGet()
                503 -> resolve503.incrementAndGet()
            }
        }
        resolveLatencySumMs.addAndGet(latencyMs.coerceAtLeast(0L))
        resolveLatencyCount.incrementAndGet()
        push(
            DiagnosticsEvent(
                kind = "resolve",
                channelId = channelId,
                httpStatus = httpStatus,
                latencyMs = latencyMs,
                ok = ok,
                detail = detail.take(120),
                atMs = System.currentTimeMillis(),
            ),
        )
    }

    fun recordSegment(
        httpStatus: Int,
        latencyMs: Long,
        ok: Boolean,
        unwrapped: Boolean,
        contentType: String = "",
        detail: String = "",
    ) {
        if (ok) segmentOk.incrementAndGet() else segmentFail.incrementAndGet()
        if (unwrapped) unwrapHits.incrementAndGet()
        segmentLatencySumMs.addAndGet(latencyMs.coerceAtLeast(0L))
        segmentLatencyCount.incrementAndGet()
        push(
            DiagnosticsEvent(
                kind = "segment",
                channelId = "",
                httpStatus = httpStatus,
                latencyMs = latencyMs,
                ok = ok,
                unwrapped = unwrapped,
                contentType = contentType.take(40),
                detail = detail.take(120),
                atMs = System.currentTimeMillis(),
            ),
        )
    }

    fun recordProxyBusy() {
        proxyBusy.incrementAndGet()
        push(
            DiagnosticsEvent(
                kind = "proxy_busy",
                ok = false,
                httpStatus = 503,
                atMs = System.currentTimeMillis(),
                detail = "content_proxy_busy",
            ),
        )
    }

    fun resetCounters() = synchronized(lock) {
        resolveOk.set(0)
        resolveFail.set(0)
        resolve504.set(0)
        resolve502.set(0)
        resolve503.set(0)
        segmentOk.set(0)
        segmentFail.set(0)
        unwrapHits.set(0)
        proxyBusy.set(0)
        resolveLatencySumMs.set(0)
        resolveLatencyCount.set(0)
        segmentLatencySumMs.set(0)
        segmentLatencyCount.set(0)
        recent.clear()
        lastByChannel.clear()
    }

    fun snapshot(): DiagnosticsSnapshot = synchronized(lock) {
        val rCount = resolveLatencyCount.get().coerceAtLeast(0L)
        val sCount = segmentLatencyCount.get().coerceAtLeast(0L)
        DiagnosticsSnapshot(
            resolveOk = resolveOk.get(),
            resolveFail = resolveFail.get(),
            resolve504 = resolve504.get(),
            resolve502 = resolve502.get(),
            resolve503 = resolve503.get(),
            segmentOk = segmentOk.get(),
            segmentFail = segmentFail.get(),
            unwrapHits = unwrapHits.get(),
            proxyBusy = proxyBusy.get(),
            avgResolveMs = if (rCount == 0L) 0.0 else resolveLatencySumMs.get().toDouble() / rCount,
            avgSegmentMs = if (sCount == 0L) 0.0 else segmentLatencySumMs.get().toDouble() / sCount,
            recent = recent.toList().asReversed(),
            lastByChannel = lastByChannel.values.toList().sortedByDescending { it.atMs }.take(20),
        )
    }

    private fun push(event: DiagnosticsEvent) = synchronized(lock) {
        if (recent.size >= RECENT_MAX) recent.removeFirst()
        recent.addLast(event)
        if (event.channelId.isNotBlank() && event.kind == "resolve") {
            lastByChannel[event.channelId] = event
            while (lastByChannel.size > 64) {
                val oldest = lastByChannel.keys.firstOrNull() ?: break
                lastByChannel.remove(oldest)
            }
        }
    }
}

@Serializable
data class DiagnosticsEvent(
    val kind: String = "",
    val channelId: String = "",
    val httpStatus: Int = 0,
    val latencyMs: Long = 0L,
    val ok: Boolean = false,
    val unwrapped: Boolean = false,
    val contentType: String = "",
    val detail: String = "",
    val atMs: Long = 0L,
)

@Serializable
data class DiagnosticsSnapshot(
    val resolveOk: Long = 0,
    val resolveFail: Long = 0,
    val resolve504: Long = 0,
    val resolve502: Long = 0,
    val resolve503: Long = 0,
    val segmentOk: Long = 0,
    val segmentFail: Long = 0,
    val unwrapHits: Long = 0,
    val proxyBusy: Long = 0,
    val avgResolveMs: Double = 0.0,
    val avgSegmentMs: Double = 0.0,
    val recent: List<DiagnosticsEvent> = emptyList(),
    val lastByChannel: List<DiagnosticsEvent> = emptyList(),
)
