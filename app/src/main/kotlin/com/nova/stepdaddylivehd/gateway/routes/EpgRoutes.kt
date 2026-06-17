package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.epg.LightEpgBuilder
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText

class EpgRoutes(
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
) {
    suspend fun epgXml(call: ApplicationCall) {
        try {
            if (client.channels.isEmpty()) {
                client.ensureChannels()
            }
            val channels = client.channels
            epgManager.maybeTriggerStaleRefresh(channels)

            val cached = epgManager.readCachedXml()
            if (cached != null && hasProgrammeData(cached)) {
                respondXml(call, cached, stale = epgManager.meta.state == "building" || epgManager.ageSeconds()?.let { it > 0 } == true)
                return
            }

            epgManager.scheduleRefresh(channels, force = cached == null)

            if (cached != null) {
                respondXml(call, cached, stale = true)
                return
            }

            val body = LightEpgBuilder.emptyXml()
            if (!hasProgrammeData(body)) {
                if (call.request.httpMethod.value == "HEAD") {
                    call.respondText("", ContentType.Application.Xml, HttpStatusCode.ServiceUnavailable)
                    return
                }
                call.respondBytes(
                    LightEpgBuilder.emptyXml(),
                    ContentType.Application.Xml,
                    HttpStatusCode.ServiceUnavailable,
                )
                return
            }
            respondXml(call, body, stale = true)
        } catch (exc: Exception) {
            val fallback = epgManager.readCachedXml()
            if (fallback != null && hasProgrammeData(fallback)) {
                respondXml(call, fallback, stale = true)
                return
            }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (exc.message ?: "epg_unavailable")),
            )
        }
    }

    private suspend fun respondXml(call: ApplicationCall, body: ByteArray, stale: Boolean) {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.response.header(HttpHeaders.ContentLength, body.size.toString())
        if (stale) {
            call.response.header("X-EPG-Status", "stale")
        }
        if (call.request.httpMethod.value == "HEAD") {
            call.respondText("", ContentType.Application.Xml)
            return
        }
        call.respondBytes(body, ContentType.Application.Xml)
    }

    private fun hasProgrammeData(body: ByteArray): Boolean {
        return "<programme" in body.toString(Charsets.UTF_8)
    }
}
