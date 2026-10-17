package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the Kotlin inheritance trap that caused FUSA ONN 504 storms:
 * [TimeoutCancellationException] extends [CancellationException], so a
 * `catch (CancellationException)` arm placed before the timeout arm swallows
 * mirror timeouts and aborts stale-good fallback.
 */
class TimeoutCancellationStaleFallbackTest {
    @Test
    fun timeoutCancellationIsCancellationException() {
        val exc: Throwable = TimeoutCancellationException("budget")
        assertTrue(exc is CancellationException)
        assertTrue(exc is TimeoutCancellationException)
    }
}
