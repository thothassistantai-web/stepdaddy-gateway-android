package com.nova.stepdaddylivehd.gateway.routes

import com.nova.stepdaddylivehd.gateway.BuildConfig
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.model.HealthResponse
import com.nova.stepdaddylivehd.gateway.model.TivimateSetup
import com.nova.stepdaddylivehd.gateway.upstream.DaddyLiveClient
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun health(call: ApplicationCall) {
        val payload = HealthResponse(
            ok = true,
            version = BuildConfig.VERSION_NAME,
            channels = client.channels.size,
            port = environment.port,
            baseUrl = environment.loopbackBase(),
            upstreamBaseUrl = client.activeBaseUrl,
            epgReady = epgManager.epgReady(),
            epgProgrammeCount = epgManager.programmeCount(),
            epgAgeSeconds = epgManager.ageSeconds(),
        )
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
        )
    }

    suspend fun tivimateSetup(call: ApplicationCall) {
        val base = environment.loopbackBase()
        val payload = TivimateSetup(
            playlist = "$base/tivimate-playlist.m3u8",
            epg = "$base/epg.xml",
            health = "$base/health",
            hint = "Add the playlist URL in TiviMate using 127.0.0.1 on this device.",
            epgReady = epgManager.epgReady(),
            epgProgrammeCount = epgManager.programmeCount(),
            epgAgeSeconds = epgManager.ageSeconds(),
        )
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
        )
    }
}
