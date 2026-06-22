package com.thothassistant.stepdaddy.gateway.network

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgPlaylistUrlResolver

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

    fun epgUrls(environment: GatewayEnvironment): List<String> =
        EpgPlaylistUrlResolver.resolveUrls(environment)

    /** Primary EPG URL for dashboard copy/open — never falls back to disabled gateway /epg.xml. */
    fun epgDisplayUrl(environment: GatewayEnvironment): String {
        val resolved = EpgPlaylistUrlResolver.resolve(environment)
        if (!resolved.isNullOrBlank()) return resolved
        return if (environment.gatewayEpgEnabled) {
            appendPath(effectiveBase(environment), "/epg.xml", environment)
        } else {
            ""
        }
    }

    fun epgUrl(environment: GatewayEnvironment): String = epgDisplayUrl(environment)

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

    /**
     * URL encoded in QR for EPG. External https feeds use the feed directly; gateway mode uses
     * loopback/LAN/tunnel + /epg.xml.
     */
    fun epgQrUrl(environment: GatewayEnvironment): String? {
        if (!environment.gatewayEpgEnabled) {
            val external = environment.externalEpgUrls()
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            return external
        }
        val base = qrBaseUrl(environment) ?: environment.loopbackBase()
        val path = appendPath(base, "/epg.xml", environment)
        return path.takeIf { it.isNotBlank() }
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
