package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.admin.GatewayAdminActions
import com.thothassistant.stepdaddy.gateway.model.AdminErrorResponse
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatch
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatchResult
import com.thothassistant.stepdaddy.gateway.model.EpgIdOverrideRequest
import com.thothassistant.stepdaddy.gateway.model.EpgNameOverrideRequest
import com.thothassistant.stepdaddy.gateway.model.LogoOverrideRequest
import com.thothassistant.stepdaddy.gateway.model.AssetImportRequest
import com.thothassistant.stepdaddy.gateway.model.CategoryMoveRequest
import com.thothassistant.stepdaddy.gateway.model.CategoryOverrideRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
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

    suspend fun getChannel(call: ApplicationCall) {
        val id = call.parameters["id"].orEmpty()
        val channel = admin.getChannel(id)
        if (channel == null) {
            call.respond(HttpStatusCode.NotFound, AdminErrorResponse(error = "not_found", message = "Channel $id not found"))
            return
        }
        call.respondText(json.encodeToString(channel), ContentType.Application.Json)
    }

    suspend fun stopGateway(call: ApplicationCall) {
        call.respondText(json.encodeToString(admin.stopGateway()), ContentType.Application.Json)
    }

    suspend fun restartGateway(call: ApplicationCall) {
        val scope = call.request.queryParameters["scope"] ?: "http"
        call.respondText(json.encodeToString(admin.restartGateway(scope)), ContentType.Application.Json)
    }

    suspend fun resolveStream(call: ApplicationCall) {
        val id = call.request.queryParameters["channelId"].orEmpty()
        if (id.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, AdminErrorResponse(error = "bad_request", message = "channelId required"))
            return
        }
        val probe = call.request.queryParameters["probe"]?.toBooleanStrictOrNull() ?: false
        call.respondText(json.encodeToString(admin.resolveStream(id, probe)), ContentType.Application.Json)
    }

    suspend fun exportAssets(call: ApplicationCall) {
        val type = call.parameters["type"].orEmpty()
        val layer = call.request.queryParameters["layer"] ?: "merged"
        call.respondText(json.encodeToString(admin.exportAssets(type, layer)), ContentType.Application.Json)
    }

    suspend fun importAssets(call: ApplicationCall) {
        val type = call.parameters["type"].orEmpty()
        val body = call.receive<AssetImportRequest>()
        call.respondText(
            json.encodeToString(admin.importAssets(type, body.entries, body.merge)),
            ContentType.Application.Json,
        )
    }

    suspend fun clearAssets(call: ApplicationCall) {
        val type = call.parameters["type"].orEmpty()
        call.respondText(json.encodeToString(admin.clearRuntimeAssets(type)), ContentType.Application.Json)
    }

    suspend fun importEpgCsv(call: ApplicationCall) {
        val csv = call.receiveText()
        call.respondText(json.encodeToString(admin.importEpgCsv(csv)), ContentType.Application.Json)
    }

    suspend fun categoryAudit(call: ApplicationCall) {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        val group = call.request.queryParameters["group"]
        call.respondText(json.encodeToString(admin.categoryAudit(limit, group)), ContentType.Application.Json)
    }

    suspend fun moveCategories(call: ApplicationCall) {
        val body = call.receive<CategoryMoveRequest>()
        call.respondText(
            json.encodeToString(admin.moveCategories(body.channelIds, body.groupTitle)),
            ContentType.Application.Json,
        )
    }

    suspend fun setCategoryOverride(call: ApplicationCall) {
        val body = call.receive<CategoryOverrideRequest>()
        call.respondText(
            json.encodeToString(admin.setCategoryOverride(body.channelId, body.channelName, body.groupTitle)),
            ContentType.Application.Json,
        )
    }

    suspend fun clearCategoryOverride(call: ApplicationCall) {
        val channelId = call.request.queryParameters["channelId"]
        val channelName = call.request.queryParameters["channelName"]
        call.respondText(
            json.encodeToString(admin.clearCategoryOverride(channelId, channelName)),
            ContentType.Application.Json,
        )
    }

    suspend fun listBackups(call: ApplicationCall) {
        val channelId = call.request.queryParameters["channelId"].orEmpty()
        if (channelId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                AdminErrorResponse(error = "bad_request", message = "Query parameter channelId is required"),
            )
            return
        }
        call.respondText(json.encodeToString(admin.listBackups(channelId)), ContentType.Application.Json)
    }

    suspend fun attachBackup(call: ApplicationCall) {
        val body = call.receive<com.thothassistant.stepdaddy.gateway.model.AdminBackupAttachRequest>()
        call.respondText(
            json.encodeToString(admin.attachBackup(body.daddyChannelId, body.supplementId)),
            ContentType.Application.Json,
        )
    }

    suspend fun removeBackup(call: ApplicationCall) {
        val body = call.receive<com.thothassistant.stepdaddy.gateway.model.AdminBackupRemoveRequest>()
        call.respondText(
            json.encodeToString(admin.removeBackup(body.daddyChannelId, body.fingerprint, body.deny)),
            ContentType.Application.Json,
        )
    }
}
