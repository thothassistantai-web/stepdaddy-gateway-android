package com.thothassistant.stepdaddy.gateway.diagnostics

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency limiter whose max slots can change at runtime (unlike a fixed [kotlinx.coroutines.sync.Semaphore]).
 */
class DynamicSlotLimiter {
    private val active = AtomicInteger(0)

    val inFlight: Int
        get() = active.get()

    /**
     * @return null if [waitMs] elapsed without acquiring a slot.
     */
    suspend fun <T> withSlot(
        limit: () -> Int,
        waitMs: Long,
        block: suspend () -> T,
    ): T? {
        val deadlineNs = System.nanoTime() + waitMs.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            val lim = limit().coerceIn(1, 32)
            while (true) {
                val cur = active.get()
                if (cur >= lim) break
                if (active.compareAndSet(cur, cur + 1)) {
                    try {
                        return block()
                    } finally {
                        // Always release even when block() is cancelled via withTimeout.
                        active.decrementAndGet()
                    }
                }
            }
            if (System.nanoTime() >= deadlineNs) return null
            delay(25)
        }
    }
}
