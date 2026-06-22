package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.epg.LightEpgBuilder
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import java.io.File

class EpgRoutes(
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val supplementSource: SupplementSource? = null,
) {
    suspend fun epgXml(call: ApplicationCall) {
        try {
            if (!epgManager.gatewayEpgEnabled()) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf(
                        "error" to "gateway_epg_disabled",
                        "hint" to "Gateway EPG is disabled. TiviMate uses the external EPG URL from the playlist.",
                    ),
                )
                return
            }
            if (client.channels.isEmpty()) {
                client.ensureChannels()
            }
            val channels = client.channels
            epgManager.maybeTriggerStaleRefresh(channels)

            val cachedFile = epgManager.servedXmlFile()
            if (cachedFile != null && epgManager.hasCachedProgrammes()) {
                respondXmlFile(
                    call,
                    cachedFile,
                    stale = epgManager.meta.state == "building" || epgManager.isServeStale(),
                )
                return
            }

            epgManager.scheduleRefresh(channels, force = cachedFile == null)

            if (cachedFile != null) {
                respondXmlFile(call, cachedFile, stale = true)
                return
            }

            if (call.request.httpMethod == HttpMethod.Head) {
                call.respondText("", ContentType.Application.Xml, HttpStatusCode.ServiceUnavailable)
                return
            }
            call.respondBytes(
                LightEpgBuilder.emptyXml(),
                ContentType.Application.Xml,
                HttpStatusCode.ServiceUnavailable,
            )
        } catch (exc: Exception) {
            val fallback = epgManager.servedXmlFile()
            if (fallback != null && epgManager.hasCachedProgrammes()) {
                respondXmlFile(call, fallback, stale = true)
                return
            }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (exc.message ?: "epg_unavailable")),
            )
        }
    }

    suspend fun sportsEpgXml(call: ApplicationCall) {
        val file = supplementSource?.sportsEpgXmlFile()
        if (file == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "sports_epg_unavailable"),
            )
            return
        }
        respondXmlFile(call, file, stale = false)
    }

    private suspend fun respondXmlFile(call: ApplicationCall, file: File, stale: Boolean) {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        if (stale) {
            call.response.header("X-EPG-Status", "stale")
        }
        call.respondFile(file)
    }
}
