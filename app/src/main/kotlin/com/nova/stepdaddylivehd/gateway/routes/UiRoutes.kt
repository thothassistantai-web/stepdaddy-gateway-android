package com.nova.stepdaddylivehd.gateway.routes

import android.content.Context
import com.nova.stepdaddylivehd.gateway.upstream.UrlSafeBase64
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes

class UiRoutes(
    context: Context,
    private val logoResolver: com.nova.stepdaddylivehd.gateway.upstream.LogoResolver,
) {
    private val defaultChannelSvg: ByteArray =
        context.assets.open("ui/default-channel.svg").use { it.readBytes() }

    suspend fun defaultChannelLogo(call: ApplicationCall) {
        respondSvg(call, defaultChannelSvg)
    }

    suspend fun channelPlaceholder(call: ApplicationCall, token: String) {
        val channelName = runCatching { UrlSafeBase64.decode(token) }
            .getOrElse { return defaultChannelLogo(call) }
        respondSvg(call, logoResolver.placeholderSvg(channelName))
    }

    private suspend fun respondSvg(call: ApplicationCall, bytes: ByteArray) {
        call.response.headers.append(
            HttpHeaders.CacheControl,
            CacheControl.MaxAge(maxAgeSeconds = 86_400).toString(),
        )
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondBytes(bytes, ContentType.Image.SVG)
    }
}
