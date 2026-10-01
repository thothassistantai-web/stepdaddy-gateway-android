package com.thothassistant.stepdaddy.gateway.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTuneRuntimeTest {
    @Test
    fun applyPatch_clampsAndOverrides() {
        RuntimeTuneRuntime.clear()
        val effective = RuntimeTuneRuntime.applyPatch(
            RuntimeTuneValues(
                contentProxyMaxConcurrent = 99,
                dlhdRaceTimeoutMs = 15_000L,
            ),
        )
        assertEquals(8, effective.contentProxyMaxConcurrent)
        assertEquals(15_000L, effective.dlhdRaceTimeoutMs)
        assertTrue(RuntimeTuneRuntime.isActive)
        RuntimeTuneRuntime.clear()
    }

    @Test
    fun compareVersionNames() {
        assertTrue(RuntimeTuneManager.compareVersionNames("3.0.66", "3.0.66") == 0)
        assertTrue(RuntimeTuneManager.compareVersionNames("3.0.66", "3.0.65") > 0)
        assertTrue(RuntimeTuneManager.compareVersionNames("3.0.60", "3.0.66") < 0)
    }
}
