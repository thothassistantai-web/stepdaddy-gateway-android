package com.thothassistant.stepdaddy.gateway.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayNetworkGuardTest {
    private val deviceLan = "192.168.1.50"

    @Test
    fun `loopback allowed in all modes`() {
        NetworkAccessMode.entries.forEach { mode ->
            val result = GatewayNetworkGuard.isAllowed(
                clientIp = "127.0.0.1",
                mode = mode,
                hasValidToken = false,
                deviceLanIp = deviceLan,
            )
            assertTrue("loopback should be allowed in $mode", result.allowed)
        }
    }

    @Test
    fun `same subnet allowed in local mode`() {
        val result = GatewayNetworkGuard.isAllowed(
            clientIp = "192.168.1.100",
            mode = NetworkAccessMode.LOCAL,
            hasValidToken = false,
            deviceLanIp = deviceLan,
        )
        assertTrue(result.allowed)
    }

    @Test
    fun `wan ip blocked in local mode`() {
        val result = GatewayNetworkGuard.isAllowed(
            clientIp = "8.8.8.8",
            mode = NetworkAccessMode.LOCAL,
            hasValidToken = false,
            deviceLanIp = deviceLan,
        )
        assertFalse(result.allowed)
    }

    @Test
    fun `wan ip blocked in default mode`() {
        val result = GatewayNetworkGuard.isAllowed(
            clientIp = "192.168.1.100",
            mode = NetworkAccessMode.DEFAULT,
            hasValidToken = false,
            deviceLanIp = deviceLan,
        )
        assertFalse(result.allowed)
    }

    @Test
    fun `remote lan client allowed without token`() {
        val result = GatewayNetworkGuard.isAllowed(
            clientIp = "192.168.1.20",
            mode = NetworkAccessMode.REMOTE,
            hasValidToken = false,
            deviceLanIp = deviceLan,
        )
        assertTrue(result.allowed)
    }

    @Test
    fun `remote wan client requires token`() {
        val withoutToken = GatewayNetworkGuard.isAllowed(
            clientIp = "203.0.113.10",
            mode = NetworkAccessMode.REMOTE,
            hasValidToken = false,
            deviceLanIp = deviceLan,
        )
        assertFalse(withoutToken.allowed)

        val withToken = GatewayNetworkGuard.isAllowed(
            clientIp = "203.0.113.10",
            mode = NetworkAccessMode.REMOTE,
            hasValidToken = true,
            deviceLanIp = deviceLan,
        )
        assertTrue(withToken.allowed)
    }

    @Test
    fun `token validation accepts query or header`() {
        assertTrue(
            GatewayNetworkGuard.hasValidToken(
                queryToken = "abc123",
                headerToken = null,
                expectedToken = "abc123",
            ),
        )
        assertTrue(
            GatewayNetworkGuard.hasValidToken(
                queryToken = null,
                headerToken = "abc123",
                expectedToken = "abc123",
            ),
        )
        assertFalse(
            GatewayNetworkGuard.hasValidToken(
                queryToken = "wrong",
                headerToken = null,
                expectedToken = "abc123",
            ),
        )
    }

    @Test
    fun `local mode ignores x-forwarded-for`() {
        val resolved = GatewayNetworkGuard.resolveClientIp(
            remoteHost = "8.8.8.8",
            xForwardedFor = "192.168.1.10",
            mode = NetworkAccessMode.LOCAL,
        )
        assertEquals("8.8.8.8", resolved)
    }

    @Test
    fun `ipv4 mapped ipv6 loopback allowed in all modes`() {
        NetworkAccessMode.entries.forEach { mode ->
            val result = GatewayNetworkGuard.isAllowed(
                clientIp = "::ffff:127.0.0.1",
                mode = mode,
                hasValidToken = false,
                deviceLanIp = deviceLan,
            )
            assertTrue("::ffff:127.0.0.1 should be loopback in $mode", result.allowed)
        }
    }

    @Test
    fun `resolveClientIp normalizes ipv4 mapped loopback`() {
        val resolved = GatewayNetworkGuard.resolveClientIp(
            remoteHost = "::ffff:127.0.0.1",
            xForwardedFor = null,
            mode = NetworkAccessMode.DEFAULT,
        )
        assertEquals("127.0.0.1", resolved)
    }

    @Test
    fun `bracketed ipv6 loopback with port is allowed in all modes`() {
        NetworkAccessMode.entries.forEach { mode ->
            val result = GatewayNetworkGuard.isAllowed(
                clientIp = "[::1]:8080",
                mode = mode,
                hasValidToken = false,
                deviceLanIp = deviceLan,
            )
            assertTrue("[::1]:8080 should be loopback in $mode", result.allowed)
        }
    }

    @Test
    fun `bind host is loopback for default mode`() {
        assertEquals("127.0.0.1", GatewayNetworkGuard.bindHost(NetworkAccessMode.DEFAULT))
        assertEquals("0.0.0.0", GatewayNetworkGuard.bindHost(NetworkAccessMode.LOCAL))
        assertEquals("0.0.0.0", GatewayNetworkGuard.bindHost(NetworkAccessMode.REMOTE))
    }
}
