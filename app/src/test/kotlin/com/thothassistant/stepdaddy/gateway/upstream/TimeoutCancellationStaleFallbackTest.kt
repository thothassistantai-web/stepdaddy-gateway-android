package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val exc: Throwable = runBlocking {
            try {
                withTimeout(1L) { awaitCancellation() }
            } catch (timeout: TimeoutCancellationException) {
                timeout
            }
        }
        assertTrue(exc is CancellationException)
        assertTrue(exc is TimeoutCancellationException)
    }
}
