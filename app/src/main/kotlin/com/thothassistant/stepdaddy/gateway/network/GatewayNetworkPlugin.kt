package com.thothassistant.stepdaddy.gateway.network

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ForbiddenResponse(
    val error: String = "forbidden",
    val message: String,
)

fun createGatewayNetworkPlugin(environment: GatewayEnvironment) = createApplicationPlugin(
    name = "GatewayNetworkGuard",
) {
    onCall { call ->
        val path = call.request.path()
        val remoteHost = call.request.local.remoteHost
        val clientIp = GatewayNetworkGuard.resolveClientIp(
            remoteHost = remoteHost,
            xForwardedFor = call.request.header("X-Forwarded-For"),
            mode = environment.networkAccessMode,
        )
        if (path == "/health" && GatewayNetworkGuard.isLoopback(clientIp)) {
            return@onCall
        }
        val queryToken = call.parameters[GatewayNetworkGuard.TOKEN_QUERY_PARAM]
        val headerToken = call.request.header(GatewayNetworkGuard.TOKEN_HEADER)
        val tokenOk = GatewayNetworkGuard.hasValidToken(
            queryToken = queryToken,
            headerToken = headerToken,
            expectedToken = environment.remoteAccessToken,
        )
        val result = GatewayNetworkGuard.isAllowed(
            clientIp = clientIp,
            mode = environment.networkAccessMode,
            hasValidToken = tokenOk,
            deviceLanIp = LanAddressResolver.lanIpv4(),
        )
        if (!result.allowed) {
            call.respond(
                HttpStatusCode.Forbidden,
                ForbiddenResponse(message = result.reason ?: "Access denied"),
            )
        }
    }
}
