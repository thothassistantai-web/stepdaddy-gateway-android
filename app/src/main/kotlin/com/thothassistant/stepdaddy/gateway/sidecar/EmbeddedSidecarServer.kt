package com.thothassistant.stepdaddy.gateway.sidecar

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Minimal loopback TVApp2-compatible sidecar for MoveOnJoy supplements only.
 * Binds 127.0.0.1:4124 on the ONN stick — no external Node process required.
 */
class EmbeddedSidecarServer(
    private val repository: EmbeddedSidecarRepository,
) {
    @Volatile
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean
        get() = engine != null

    fun start() {
        if (engine != null) return
        try {
            engine = embeddedServer(
                CIO,
                host = "127.0.0.1",
                port = SidecarConfig.PORT,
                configure = {
                    connectionIdleTimeoutSeconds = 120
                    reuseAddress = true
                },
            ) {
                routing {
                    get("/health") {
                        call.respond(
                            mapOf(
                                "ok" to true,
                                "service" to "embedded-sidecar",
                                "moveOnJoyChannels" to repository.channelCount(),
                                "port" to SidecarConfig.PORT,
                            ),
                        )
                    }
                    get("/playlist.m3u8") {
                        call.respondText(
                            repository.playlistBody(),
                            ContentType("application", "vnd.apple.mpegurl"),
                        )
                    }
                    get("/xmltv.xml.gz") {
                        val file = repository.epgGzipFile()
                        if (file == null) {
                            call.respond(HttpStatusCode.NotFound, "epg_unavailable")
                            return@get
                        }
                        call.respondBytes(file.readBytes(), ContentType.Application.GZip)
                    }
                    get("/xmltv.xml") {
                        val file = repository.epgGzipFile()
                        if (file == null) {
                            call.respond(HttpStatusCode.NotFound, "epg_unavailable")
                            return@get
                        }
                        call.respondText(
                            """<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="StepDaddy Embedded Sidecar"/>""",
                            ContentType.Text.Xml,
                        )
                    }
                }
            }.start(wait = false)
            Log.i(TAG, "Listening on 127.0.0.1:${SidecarConfig.PORT}")
        } catch (exc: Exception) {
            engine = null
            Log.e(TAG, "Failed to bind sidecar port ${SidecarConfig.PORT}", exc)
            throw exc
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 2_000)
        engine = null
    }

    companion object {
        private const val TAG = "EmbeddedSidecarServer"
    }
}
