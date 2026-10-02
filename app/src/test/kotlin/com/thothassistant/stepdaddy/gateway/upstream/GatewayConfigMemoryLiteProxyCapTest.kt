package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.diagnostics.RuntimeTuneRuntime
import com.thothassistant.stepdaddy.gateway.diagnostics.RuntimeTuneValues
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayConfigMemoryLiteProxyCapTest {
    @Test
    fun memoryLiteCapsTunedProxyConcurrency() {
        RuntimeTuneRuntime.clear()
        RuntimeTuneRuntime.applyPatch(
            RuntimeTuneValues(contentProxyMaxConcurrent = 8),
        )
        try {
            GatewayConfig.memoryLiteActive = true
            assertEquals(3, GatewayConfig.CONTENT_PROXY_MAX_CONCURRENT)
            GatewayConfig.memoryLiteActive = false
            assertEquals(8, GatewayConfig.CONTENT_PROXY_MAX_CONCURRENT)
        } finally {
            GatewayConfig.memoryLiteActive = false
            RuntimeTuneRuntime.clear()
        }
    }
}
