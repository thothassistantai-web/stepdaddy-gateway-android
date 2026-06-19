package com.nova.stepdaddylivehd.gateway.ui

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetworkHelper {
    /**
     * Returns the device's LAN IPv4 address, preferring wlan0 then eth0,
     * then any non-loopback site-local or global IPv4 on an up interface.
     */
    fun lanIpv4(): String? {
        preferredInterfaceNames().forEach { name ->
            addressOnInterface(name)?.let { return it }
        }
        return NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .mapNotNull { addr ->
                if (addr.isLoopbackAddress || addr !is Inet4Address) return@mapNotNull null
                val host = addr.hostAddress ?: return@mapNotNull null
                if (host.startsWith("169.254.")) return@mapNotNull null
                host
            }
            .firstOrNull()
    }

    private fun preferredInterfaceNames(): List<String> = listOf("wlan0", "eth0", "en0")

    private fun addressOnInterface(name: String): String? = runCatching {
        val iface = NetworkInterface.getByName(name) ?: return null
        if (!iface.isUp || iface.isLoopback) return null
        iface.inetAddresses.toList()
            .asSequence()
            .filter { !it.isLoopbackAddress && it is Inet4Address }
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("169.254.") }
    }.getOrNull()
}
