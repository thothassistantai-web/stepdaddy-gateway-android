package com.thothassistant.stepdaddy.gateway.streamvault

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.network.GatewayUrlBuilder
import com.thothassistant.stepdaddy.gateway.network.LanAddressResolver
import com.thothassistant.stepdaddy.gateway.routes.PlaylistPaths
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object StreamVaultPluginSupport {
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun manifestJson(): String =
        """
        {
          "schemaVersion": 1,
          "id": "${StreamVaultPluginContract.PLUGIN_ID}",
          "name": "StepDaddy Gateway",
          "versionName": "${BuildConfig.VERSION_NAME}",
          "versionCode": ${BuildConfig.VERSION_CODE},
          "description": "Exposes the StepDaddy Gateway M3U playlist to StreamVault.",
          "providerName": "StepDaddy Gateway",
          "configurationMode": "${StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA}",
          "configurationActivityAction": "",
          "capabilities": [
            "${StreamVaultPluginContract.CAPABILITY_PROVIDER_M3U}",
            "${StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE}",
            "${StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA}"
          ]
        }
        """.trimIndent()

    fun configurationSchemaJson(): String =
        """
        {
          "schemaVersion": 1,
          "title": "StepDaddy Gateway",
          "description": "Settings rendered by StreamVault.",
          "sections": [
            {
              "id": "connection",
              "title": "Connection",
              "description": "Gateway HTTP endpoint on this device.",
              "fields": [
                {
                  "key": "${StreamVaultPluginContract.CONFIG_KEY_GATEWAY_BASE}",
                  "type": "url",
                  "label": "Gateway URL",
                  "placeholder": "${BuildConfig.DEFAULT_API_URL}",
                  "required": true
                },
                {
                  "key": "${StreamVaultPluginContract.CONFIG_KEY_LAN_MODE}",
                  "type": "boolean",
                  "label": "LAN mode",
                  "description": "Use the device LAN address instead of 127.0.0.1 for provider URLs."
                },
                {
                  "key": "${StreamVaultPluginContract.CONFIG_KEY_STATUS}",
                  "type": "info",
                  "label": "Status",
                  "readOnly": true
                }
              ]
            }
          ],
          "actions": [
            {
              "id": "${StreamVaultPluginContract.ACTION_TEST_CONNECTION}",
              "label": "Test connection",
              "description": "Validate gateway health and channel readiness.",
              "refreshAfterRun": true
            }
          ]
        }
        """.trimIndent()

    fun configurationValuesJson(
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
    ): String {
        val base = resolveGatewayBase(prefs, environment)
        val status = probeGatewayStatus(base)
        return """
        {
          "${StreamVaultPluginContract.CONFIG_KEY_GATEWAY_BASE}": "$base",
          "${StreamVaultPluginContract.CONFIG_KEY_LAN_MODE}": ${prefs.lanMode},
          "${StreamVaultPluginContract.CONFIG_KEY_STATUS}": "${status.label.replace("\"", "\\\"")}"
        }
        """.trimIndent()
    }

    fun applyConfigurationValues(
        prefs: StreamVaultPluginSettings,
        valuesJson: String,
    ): String? {
        val values = runCatching {
            json.decodeFromString<JsonObject>(valuesJson)
        }.getOrElse {
            return "Invalid configuration JSON"
        }
        values[StreamVaultPluginContract.CONFIG_KEY_GATEWAY_BASE]?.let { element ->
            val raw = element.jsonPrimitive.content.trim()
            if (raw.isBlank()) {
                return "Gateway URL is required"
            }
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                return "Gateway URL must start with http:// or https://"
            }
            prefs.gatewayBaseUrl = raw.trimEnd('/')
        }
        values[StreamVaultPluginContract.CONFIG_KEY_LAN_MODE]?.let { element ->
            prefs.lanMode = element.jsonPrimitive.booleanOrNull ?: false
        }
        return null
    }

    fun providerUrl(
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
    ): String {
        val base = resolveGatewayBase(prefs, environment)
        return "$base${PlaylistPaths.STREAMVAULT}"
    }

    fun providerEpgUrl(
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
    ): String {
        val base = resolveGatewayBase(prefs, environment)
        return "$base/epg.xml"
    }

    fun statusLabel(
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
    ): GatewayProbe = probeGatewayStatus(resolveGatewayBase(prefs, environment))

    fun resolveGatewayBase(
        prefs: StreamVaultPluginSettings,
        environment: GatewayEnvironment?,
    ): String {
        if (environment != null && prefs.gatewayBaseUrl == BuildConfig.DEFAULT_API_URL.trimEnd('/')) {
            return GatewayUrlBuilder.effectiveBase(environment).trimEnd('/')
        }
        val configured = prefs.gatewayBaseUrl.trimEnd('/')
        if (!prefs.lanMode) {
            return configured
        }
        val lanIp = LanAddressResolver.lanIpv4()
        if (lanIp.isNullOrBlank()) {
            return configured
        }
        val port = environment?.port ?: BuildConfig.DEFAULT_PORT
        return "http://$lanIp:$port"
    }

    fun probeGatewayStatus(baseUrl: String): GatewayProbe =
        runCatching {
            probeExecutor.submit<GatewayProbe> {
                probeGatewayStatusBlocking(baseUrl)
            }.get(5, TimeUnit.SECONDS)
        }.getOrElse { error ->
            GatewayProbe(
                ready = false,
                label = "Offline",
                message = error.message ?: "Gateway unreachable",
            )
        }

    private fun probeGatewayStatusBlocking(baseUrl: String): GatewayProbe {
        val healthUrl = "${baseUrl.trimEnd('/')}/health?lite=1"
        return runCatching {
            val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    return GatewayProbe(
                        ready = false,
                        label = "HTTP $code",
                        message = "Gateway health probe failed",
                    )
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val payload = json.decodeFromString<JsonObject>(body)
                val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull == true
                val starting = payload["starting"]?.jsonPrimitive?.booleanOrNull == true
                val channels = payload["channels"]?.jsonPrimitive?.intOrNull ?: 0
                val supplement = payload["supplementChannels"]?.jsonPrimitive?.intOrNull ?: 0
                val total = channels + supplement
                return when {
                    !ok -> GatewayProbe(false, "Unavailable", "Gateway reported ok=false")
                    starting || total <= 0 -> GatewayProbe(
                        false,
                        "Starting",
                        "Gateway is still loading channels",
                    )
                    else -> GatewayProbe(true, "Ready ($total channels)", "Gateway is ready")
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            GatewayProbe(
                ready = false,
                label = "Offline",
                message = error.message ?: "Gateway unreachable",
            )
        }
    }

    data class GatewayProbe(
        val ready: Boolean,
        val label: String,
        val message: String,
    )
}
