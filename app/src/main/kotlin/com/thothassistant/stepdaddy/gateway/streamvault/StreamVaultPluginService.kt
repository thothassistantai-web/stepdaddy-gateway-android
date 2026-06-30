package com.thothassistant.stepdaddy.gateway.streamvault

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.GatewayStartHelper
import com.thothassistant.stepdaddy.gateway.ServerService
import kotlinx.coroutines.runBlocking

/**
 * Embedded StreamVault companion plugin. StreamVault discovers this service via
 * [StreamVaultPluginContract.ACTION_PLUGIN_SERVICE] and imports the gateway M3U URL.
 */
class StreamVaultPluginService : Service() {
    private val prefs by lazy { StreamVaultPluginPrefs(this) }
    private val environment: GatewayEnvironment? by lazy {
        runCatching { (applicationContext as GatewayApp).gatewayEnvironment }.getOrNull()
    }

    private val incomingHandler = IncomingHandler()
    private val messenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val request = msg.data ?: Bundle.EMPTY
            val requestId = request.getString(StreamVaultPluginContract.KEY_REQUEST_ID).orEmpty()
            val reply = Message.obtain(null, msg.what)
            reply.replyTo = msg.replyTo
            reply.data = handleRequest(msg.what, request).apply {
                putInt(StreamVaultPluginContract.KEY_API_VERSION, StreamVaultPluginContract.API_VERSION)
                putString(StreamVaultPluginContract.KEY_REQUEST_ID, requestId)
            }
            runCatching { msg.replyTo?.send(reply) }
        }
    }

    private fun handleRequest(what: Int, request: Bundle): Bundle {
        return when (what) {
            StreamVaultPluginContract.MSG_GET_MANIFEST -> successBundle {
                putString(StreamVaultPluginContract.KEY_MANIFEST_JSON, StreamVaultPluginSupport.manifestJson())
            }
            StreamVaultPluginContract.MSG_SET_ENABLED -> {
                val enabled = request.getBoolean(StreamVaultPluginContract.KEY_ENABLED, false)
                prefs.enabled = enabled
                successBundle {
                    putString(
                        StreamVaultPluginContract.KEY_MESSAGE,
                        if (enabled) "StepDaddy Gateway plugin enabled" else "StepDaddy Gateway plugin disabled",
                    )
                }
            }
            StreamVaultPluginContract.MSG_GET_STATUS -> {
                val probe = StreamVaultPluginSupport.statusLabel(prefs, environment)
                successBundle {
                    putString(StreamVaultPluginContract.KEY_STATUS_LABEL, probe.label)
                    putString(StreamVaultPluginContract.KEY_MESSAGE, probe.message)
                }
            }
            StreamVaultPluginContract.MSG_GET_PROVIDER_URL -> {
                if (!prefs.enabled) {
                    failureBundle("Plugin is disabled")
                } else {
                    var probe = StreamVaultPluginSupport.statusLabel(prefs, environment)
                    if (!probe.ready) {
                        runBlocking {
                            GatewayStartHelper.startIfNeeded(
                                this@StreamVaultPluginService,
                                "StreamVaultPlugin-provider",
                            )
                            GatewayStartHelper.ensureGatewayReady(this@StreamVaultPluginService)
                        }
                        probe = StreamVaultPluginSupport.statusLabel(prefs, environment)
                    }
                    if (!probe.ready) {
                        failureBundle(probe.message)
                    } else {
                        successBundle {
                            putString(
                                StreamVaultPluginContract.KEY_URL,
                                StreamVaultPluginSupport.providerUrl(prefs, environment),
                            )
                            putString(
                                StreamVaultPluginContract.KEY_EPG_URL,
                                StreamVaultPluginSupport.providerEpgUrl(prefs, environment),
                            )
                            putString(StreamVaultPluginContract.KEY_PROVIDER_NAME, "StepDaddy Gateway")
                        }
                    }
                }
            }
            StreamVaultPluginContract.MSG_PREPARE_PLAYBACK -> {
                val inputUrl = request.getString(StreamVaultPluginContract.KEY_INPUT_URL).orEmpty()
                val supplementSource = runCatching {
                    (applicationContext as GatewayApp).supplementSource
                }.getOrNull()
                val prepared = StreamVaultPluginPlaybackPrepare.prepare(
                    inputUrl = inputUrl,
                    prefs = prefs,
                    environment = environment,
                    supplementSource = supplementSource,
                )
                if (!prepared.handled) {
                    successBundle { putBoolean(StreamVaultPluginContract.KEY_HANDLED, false) }
                } else {
                    successBundle {
                        putBoolean(StreamVaultPluginContract.KEY_HANDLED, true)
                        putString(StreamVaultPluginContract.KEY_OUTPUT_URL, prepared.outputUrl.orEmpty())
                        prepared.headersJson?.let {
                            putString(StreamVaultPluginContract.KEY_HEADERS_JSON, it)
                        }
                        prepared.userAgent?.let {
                            putString(StreamVaultPluginContract.KEY_USER_AGENT, it)
                        }
                        prepared.message?.let {
                            putString(StreamVaultPluginContract.KEY_MESSAGE, it)
                        }
                        prepared.audioJson?.let {
                            putString(StreamVaultPluginContract.KEY_AUDIO_JSON, it)
                        }
                    }
                }
            }
            StreamVaultPluginContract.MSG_REWRITE_CAST_URL -> successBundle {
                putBoolean(StreamVaultPluginContract.KEY_HANDLED, false)
            }
            StreamVaultPluginContract.MSG_GET_CONFIGURATION_SCHEMA -> successBundle {
                putString(
                    StreamVaultPluginContract.KEY_CONFIGURATION_SCHEMA_JSON,
                    StreamVaultPluginSupport.configurationSchemaJson(),
                )
            }
            StreamVaultPluginContract.MSG_GET_CONFIGURATION_VALUES -> successBundle {
                putString(
                    StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON,
                    StreamVaultPluginSupport.configurationValuesJson(prefs, environment),
                )
            }
            StreamVaultPluginContract.MSG_SET_CONFIGURATION_VALUES -> {
                val valuesJson = request
                    .getString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON)
                    .orEmpty()
                val error = StreamVaultPluginSupport.applyConfigurationValues(prefs, valuesJson)
                if (error != null) {
                    failureBundle(error)
                } else {
                    successBundle()
                }
            }
            StreamVaultPluginContract.MSG_RUN_CONFIGURATION_ACTION -> {
                val actionId = request
                    .getString(StreamVaultPluginContract.KEY_CONFIGURATION_ACTION_ID)
                    .orEmpty()
                if (actionId != StreamVaultPluginContract.ACTION_TEST_CONNECTION) {
                    failureBundle("Unknown action: $actionId")
                } else {
                    val probe = StreamVaultPluginSupport.statusLabel(prefs, environment)
                    if (probe.ready) {
                        successBundle { putString(StreamVaultPluginContract.KEY_MESSAGE, probe.message) }
                    } else {
                        failureBundle(probe.message)
                    }
                }
            }
            StreamVaultPluginContract.MSG_ENSURE_GATEWAY -> {
                val ready = runBlocking {
                    if (!ServerService.isServiceActive) {
                        GatewayStartHelper.startIfNeeded(this@StreamVaultPluginService, "StreamVaultPlugin")
                    } else {
                        GatewayStartHelper.startIfNeeded(this@StreamVaultPluginService, "StreamVaultPlugin-nudge")
                    }
                    GatewayStartHelper.ensureGatewayReady(this@StreamVaultPluginService)
                }
                if (ready) {
                    val probe = StreamVaultPluginSupport.statusLabel(prefs, environment)
                    successBundle {
                        putString(StreamVaultPluginContract.KEY_MESSAGE, probe.message)
                        putString(StreamVaultPluginContract.KEY_STATUS_LABEL, probe.label)
                    }
                } else {
                    failureBundle("Gateway did not become ready")
                }
            }
            else -> failureBundle("Unsupported message: $what")
        }
    }

    private fun successBundle(configure: Bundle.() -> Unit = {}): Bundle =
        Bundle().apply {
            putBoolean(StreamVaultPluginContract.KEY_SUCCESS, true)
            configure()
        }

    private fun failureBundle(message: String): Bundle =
        Bundle().apply {
            putBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)
            putString(StreamVaultPluginContract.KEY_MESSAGE, message)
        }
}
