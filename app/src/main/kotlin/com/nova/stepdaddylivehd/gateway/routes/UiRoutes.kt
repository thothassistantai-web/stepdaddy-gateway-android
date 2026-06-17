package com.nova.stepdaddylivehd.gateway.routes

import android.content.Context
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes

class UiRoutes(context: Context) {
    private val defaultChannelSvg: ByteArray =
        context.assets.open("ui/default-channel.svg").use { it.readBytes() }

    suspend fun defaultChannelLogo(call: ApplicationCall) {
        call.response.headers.append(
            HttpHeaders.CacheControl,
            CacheControl.MaxAge(maxAgeSeconds = 86_400).toString(),
        )
        call.respondBytes(
            defaultChannelSvg,
            ContentType.Image.SVG,
        )
    }
}
