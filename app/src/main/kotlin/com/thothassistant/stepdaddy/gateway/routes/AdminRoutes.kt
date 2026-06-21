package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.admin.GatewayAdminActions
import com.thothassistant.stepdaddy.gateway.model.AdminErrorResponse
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatch
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatchResult
import com.thothassistant.stepdaddy.gateway.model.EpgIdOverrideRequest
import com.thothassistant.stepdaddy.gateway.model.EpgNameOverrideRequest
import com.thothassistant.stepdaddy.gateway.model.LogoOverrideRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AdminRoutes(
    private val admin: GatewayAdminActions,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun discovery(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.discovery()), ContentType.Application.Json)
    }

    suspend fun getSettings(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.getSettings()), ContentType.Application.Json)
    }

    suspend fun patchSettings(call: ApplicationCall) {
        val patch = call.receive<AdminSettingsPatch>()
        val (snapshot, requiresRestart) = admin.patchSettings(patch)
        val payload = AdminSettingsPatchResult(
            message = buildString {
                append("Settings updated")
                if (requiresRestart) append(" — restart gateway for port/mode changes")
            },
            requiresRestart = requiresRestart,
            settings = snapshot,
        )
        call.respondText(json.encodeToString(payload), ContentType.Application.Json)
    }

    suspend fun searchChannels(call: ApplicationCall) {
        val query = call.request.queryParameters["q"].orEmpty()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        if (query.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AdminErrorResponse(error = "bad_request", message = "Query parameter q is required"),
            )
            return
        }
        call.respondText(
            json.encodeToString(admin.searchChannels(query, limit)),
            ContentType.Application.Json,
        )
    }

    suspend fun refreshChannels(call: ApplicationCall) {
        val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: true
        call.respondText(json.encodeToString(admin.refreshChannels(force)), ContentType.Application.Json)
    }

    suspend fun refreshSupplements(call: ApplicationCall) {
        val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: true
        call.respondText(json.encodeToString(admin.refreshSupplements(force)), ContentType.Application.Json)
    }

    suspend fun refreshEpg(call: ApplicationCall) {
        val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: true
        call.respondText(json.encodeToString(admin.refreshEpg(force)), ContentType.Application.Json)
    }

    suspend fun refreshLogos(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.refreshLogos()), ContentType.Application.Json)
    }

    suspend fun refreshTvgIds(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.refreshTvgIds()), ContentType.Application.Json)
    }

    suspend fun prewarmPlaylist(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.prewarmPlaylist()), ContentType.Application.Json)
    }

    suspend fun setLogoOverride(call: ApplicationCall) {
        val body = call.receive<LogoOverrideRequest>()
        call.respondText(
            json.encodeToString(admin.setLogoOverride(body.channelName, body.url)),
            ContentType.Application.Json,
        )
    }

    suspend fun clearLogoOverride(call: ApplicationCall) {
        val name = call.request.queryParameters["channelName"].orEmpty()
        if (name.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AdminErrorResponse(error = "bad_request", message = "Query parameter channelName is required"),
            )
            return
        }
        call.respondText(json.encodeToString(admin.clearLogoOverride(name)), ContentType.Application.Json)
    }

    suspend fun setEpgNameOverride(call: ApplicationCall) {
        val body = call.receive<EpgNameOverrideRequest>()
        call.respondText(
            json.encodeToString(admin.setEpgNameOverride(body.channelName, body.tvgId)),
            ContentType.Application.Json,
        )
    }

    suspend fun setEpgIdOverride(call: ApplicationCall) {
        val body = call.receive<EpgIdOverrideRequest>()
        call.respondText(
            json.encodeToString(admin.setEpgIdOverride(body.channelId, body.tvgId)),
            ContentType.Application.Json,
        )
    }

    suspend fun resolveLogo(call: ApplicationCall) {
        val name = call.request.queryParameters["channelName"].orEmpty()
        if (name.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AdminErrorResponse(error = "bad_request", message = "Query parameter channelName is required"),
            )
            return
        }
        val tvgId = call.request.queryParameters["tvgId"]
        call.respondText(
            json.encodeToString(admin.resolveLogo(name, tvgId)),
            ContentType.Application.Json,
        )
    }

    suspend fun resolveEpg(call: ApplicationCall) {
        val name = call.request.queryParameters["channelName"].orEmpty()
        if (name.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AdminErrorResponse(error = "bad_request", message = "Query parameter channelName is required"),
            )
            return
        }
        call.respondText(json.encodeToString(admin.resolveEpg(name)), ContentType.Application.Json)
    }
}
