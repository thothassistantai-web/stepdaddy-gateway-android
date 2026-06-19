package com.thothassistant.stepdaddy.gateway.network

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment

object GatewayUrlBuilder {
    fun effectiveBase(environment: GatewayEnvironment): String =
        when (environment.networkAccessMode) {
            NetworkAccessMode.DEFAULT -> environment.loopbackBase()
            NetworkAccessMode.LOCAL -> {
                val lanIp = LanAddressResolver.lanIpv4()
                if (lanIp.isNullOrBlank()) environment.loopbackBase() else "http://$lanIp:${environment.port}"
            }
            NetworkAccessMode.REMOTE -> {
                val tunnel = environment.remoteGatewayUrl.trim().trimEnd('/')
                if (tunnel.isNotBlank()) {
                    tunnel
                } else {
                    val lanIp = LanAddressResolver.lanIpv4()
                    if (lanIp.isNullOrBlank()) environment.loopbackBase() else "http://$lanIp:${environment.port}"
                }
            }
        }

    fun playlistUrl(environment: GatewayEnvironment): String =
        appendPath(effectiveBase(environment), "/tivimate-playlist.m3u8", environment)

    fun epgUrl(environment: GatewayEnvironment): String =
        appendPath(effectiveBase(environment), "/epg.xml", environment)

    fun healthUrl(environment: GatewayEnvironment): String =
        appendPath(environment.loopbackBase(), "/health", environment, forceLoopback = true)

    fun qrBaseUrl(environment: GatewayEnvironment): String? =
        when (environment.networkAccessMode) {
            NetworkAccessMode.DEFAULT -> environment.loopbackBase()
            NetworkAccessMode.LOCAL -> {
                val lanIp = LanAddressResolver.lanIpv4()
                if (lanIp == null) null else "http://$lanIp:${environment.port}"
            }
            NetworkAccessMode.REMOTE -> {
                val tunnel = environment.remoteGatewayUrl.trim().trimEnd('/')
                tunnel.takeIf { it.isNotEmpty() }
            }
        }

    fun appendAccessToken(url: String, token: String): String {
        if (token.isBlank()) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url$separator${GatewayNetworkGuard.TOKEN_QUERY_PARAM}=$token"
    }

    private fun appendPath(
        base: String,
        path: String,
        environment: GatewayEnvironment,
        forceLoopback: Boolean = false,
    ): String {
        val normalizedBase = base.trimEnd('/')
        val full = "$normalizedBase$path"
        if (forceLoopback) return full
        if (environment.networkAccessMode != NetworkAccessMode.REMOTE) return full
        val token = environment.remoteAccessToken
        if (token.isBlank()) return full
        val lanIp = LanAddressResolver.lanIpv4()
        if (!lanIp.isNullOrBlank() && normalizedBase.contains(lanIp)) {
            return full
        }
        if (normalizedBase.startsWith("http://127.0.0.1") ||
            normalizedBase.startsWith("http://localhost")
        ) {
            return full
        }
        return appendAccessToken(full, token)
    }
}
