package com.thothassistant.stepdaddy.gateway.routes

import android.content.Context
import android.provider.Settings
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.TiviMateEventStore
import com.thothassistant.stepdaddy.gateway.model.TiviMateEventIngest
import com.thothassistant.stepdaddy.gateway.model.TiviMateEventIngestResponse
import com.thothassistant.stepdaddy.gateway.model.TiviMateEventsResponse
import com.thothassistant.stepdaddy.gateway.model.TiviMateHandshake
import com.thothassistant.stepdaddy.gateway.model.TiviMateStateResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TiviMateRoutes(
    private val appContext: Context,
    private val environment: GatewayEnvironment,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun postEvent(call: ApplicationCall) {
        val body = runCatching { call.receive<TiviMateEventIngest>().toEvent() }.getOrElse {
            call.respondText(
                json.encodeToString(mapOf("ok" to false, "error" to "invalid_json")),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        if (body.event.isBlank()) {
            call.respondText(
                json.encodeToString(mapOf("ok" to false, "error" to "missing_event")),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        TiviMateEventStore.append(body)
        call.respondText(
            json.encodeToString(
                TiviMateEventIngestResponse(
                    ok = true,
                    buffered = TiviMateEventStore.size(),
                ),
            ),
            ContentType.Application.Json,
        )
    }

    suspend fun getEvents(call: ApplicationCall) {
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val events = TiviMateEventStore.snapshot(since)
        call.respondText(
            json.encodeToString(
                TiviMateEventsResponse(
                    events = events,
                    count = events.size,
                    since = since,
                ),
            ),
            ContentType.Application.Json,
        )
    }

    suspend fun handshake(call: ApplicationCall) {
        val base = environment.loopbackBase()
        val payload = TiviMateHandshake(
            deviceId = resolveDeviceId(),
            gatewayVersion = BuildConfig.VERSION_NAME,
            bootChannel = null,
            features = listOf("events", "state"),
            gatewayBase = base,
            eventsUrl = "$base/tivimate-events",
            stateUrl = "$base/tivimate-state",
        )
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
        )
    }

    suspend fun state(call: ApplicationCall) {
        val probe = TiviMateController.probeState()
        val payload = TiviMateStateResponse(
            reachable = probe.reachable,
            statusCode = probe.statusCode,
            state = probe.state,
            error = probe.error,
        )
        val status = HttpStatusCode.OK
        call.respondText(
            json.encodeToString(payload),
            ContentType.Application.Json,
            status,
        )
    }

    private fun resolveDeviceId(): String {
        return runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}
