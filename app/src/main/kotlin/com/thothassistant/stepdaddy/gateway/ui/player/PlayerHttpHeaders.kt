package com.thothassistant.stepdaddy.gateway.ui.player

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.network.GatewayUrlBuilder
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig
import okhttp3.Request

object PlayerHttpHeaders {
    fun streamUrl(environment: GatewayEnvironment, channelId: String): String {
        val base = environment.loopbackBase().trimEnd('/')
        return "$base/tivimate-stream/$channelId.m3u8"
    }

    fun playlistUrl(environment: GatewayEnvironment): String =
        GatewayUrlBuilder.tivimatePlaylistUrl(environment)

    fun requestProperties(environment: GatewayEnvironment): Map<String, String> {
        val origin = environment.dlhdBaseUrl.trimEnd('/')
        return mapOf(
            "Referer" to "$origin/",
            "Origin" to origin,
        )
    }

    fun applyToRequest(
        builder: Request.Builder,
        environment: GatewayEnvironment,
    ): Request.Builder {
        builder.header("User-Agent", GatewayConfig.TIVIMATE_USER_AGENT)
        requestProperties(environment).forEach { (key, value) ->
            builder.header(key, value)
        }
        return builder
    }
}
