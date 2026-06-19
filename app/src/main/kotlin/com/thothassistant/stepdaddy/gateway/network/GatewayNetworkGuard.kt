package com.thothassistant.stepdaddy.gateway.network

data class GuardResult(
    val allowed: Boolean,
    val reason: String? = null,
)

object GatewayNetworkGuard {
    const val TOKEN_QUERY_PARAM = "access_token"
    const val TOKEN_HEADER = "X-StepDaddy-Token"

    fun isLoopback(clientIp: String): Boolean {
        val normalized = normalizeIp(clientIp)
        return normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized.equals("localhost", ignoreCase = true)
    }

    fun resolveClientIp(remoteHost: String, xForwardedFor: String?, mode: NetworkAccessMode): String {
        val direct = normalizeIp(remoteHost)
        if (mode != NetworkAccessMode.LOCAL && isLoopback(direct) && !xForwardedFor.isNullOrBlank()) {
            val firstHop = xForwardedFor.split(',').firstOrNull()?.trim().orEmpty()
          if (firstHop.isNotEmpty()) {
            return normalizeIp(firstHop)
          }
        }
        return direct
    }

    fun hasValidToken(
        queryToken: String?,
        headerToken: String?,
        expectedToken: String,
    ): Boolean {
        if (expectedToken.isBlank()) return false
        val provided = queryToken?.trim().orEmpty().ifBlank { headerToken?.trim().orEmpty() }
        return provided.isNotEmpty() && provided == expectedToken
    }

    fun isAllowed(
        clientIp: String,
        mode: NetworkAccessMode,
        hasValidToken: Boolean,
        deviceLanIp: String?,
        prefixLength: Int = 24,
    ): GuardResult {
        val ip = normalizeIp(clientIp)
        if (isLoopback(ip)) {
            return GuardResult(allowed = true)
        }

        return when (mode) {
            NetworkAccessMode.DEFAULT -> GuardResult(
                allowed = false,
                reason = "Default mode allows loopback clients only",
            )
            NetworkAccessMode.LOCAL -> {
                val lanIp = deviceLanIp
                if (lanIp.isNullOrBlank()) {
                    GuardResult(allowed = false, reason = "No LAN interface detected")
                } else if (LanAddressResolver.isSameSubnet(ip, lanIp, prefixLength)) {
                    GuardResult(allowed = true)
                } else {
                    GuardResult(allowed = false, reason = "Client is outside the local subnet")
                }
            }
            NetworkAccessMode.REMOTE -> {
                val lanIp = deviceLanIp
                if (!lanIp.isNullOrBlank() && LanAddressResolver.isSameSubnet(ip, lanIp, prefixLength)) {
                    GuardResult(allowed = true)
                } else if (hasValidToken) {
                    GuardResult(allowed = true)
                } else {
                    GuardResult(
                        allowed = false,
                        reason = "Remote access requires a valid access token for non-LAN clients",
                    )
                }
            }
        }
    }

    fun bindHost(mode: NetworkAccessMode): String =
        when (mode) {
            NetworkAccessMode.DEFAULT -> "127.0.0.1"
            NetworkAccessMode.LOCAL, NetworkAccessMode.REMOTE -> "0.0.0.0"
        }

    private fun normalizeIp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("/")) {
            return trimmed.removePrefix("/")
        }
        if (trimmed.startsWith("[")) {
            return trimmed.trim('[', ']')
        }
        return trimmed.substringBefore(':').substringBefore('%')
    }
}
