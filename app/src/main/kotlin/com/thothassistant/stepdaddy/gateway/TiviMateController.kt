package com.thothassistant.stepdaddy.gateway

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.model.TiviMateChannelsPayload
import com.thothassistant.stepdaddy.gateway.model.TiviMateChannelsResponse
import com.thothassistant.stepdaddy.gateway.model.TiviMateEvent
import com.thothassistant.stepdaddy.gateway.model.TiviMateHttpStatus
import com.thothassistant.stepdaddy.gateway.model.TiviMatePlayerState
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/**
 * TiViMate player control surface backed by manifest RE (4.6.1 + 5.3.3) and the
 * StepDaddy 4.6.1 patch (`research/tivimate-apk/stepdaddy-patch`).
 *
 * Stock TiViMate only exposes [MAIN_ACTIVITY]. The patched build adds
 * `stepdaddy://` URIs, explicit broadcast actions, and loopback HTTP on port 4617.
 */
object TiviMateController {
    const val DADDY_LIVE_PACKAGE = TiviMateWatch.DADDY_LIVE_PACKAGE
    const val LEGACY_DADDY_LIVE_PACKAGE = TiviMateWatch.LEGACY_DADDY_LIVE_PACKAGE
    const val LEGACY_TIVIMATE_PACKAGE = TiviMateWatch.LEGACY_TIVIMATE_PACKAGE
    /** Expected applicationId for new DaddyLive TV APK releases (2.3.0+). */
    const val PACKAGE = DADDY_LIVE_PACKAGE

    const val MAIN_ACTIVITY_CLASS = "ar.tvplayer.tv.ui.MainActivity"
    const val BRIDGE_ACTIVITY_CLASS = "ar.tvplayer.tv.stepdaddy.StepDaddyBridgeActivity"
    const val COMMAND_RECEIVER_CLASS = "ar.tvplayer.tv.stepdaddy.StepDaddyCommandReceiver"

    private const val DADDY_ACTION_PREFIX = "$DADDY_LIVE_PACKAGE.action"
    private const val LEGACY_DADDY_ACTION_PREFIX = "$LEGACY_DADDY_LIVE_PACKAGE.action"
    private const val LEGACY_ACTION_PREFIX = "$LEGACY_TIVIMATE_PACKAGE.action"

    const val ACTION_SETUP = "$DADDY_ACTION_PREFIX.STEPDADDY_SETUP"
    const val ACTION_TUNE = "$DADDY_ACTION_PREFIX.STEPDADDY_TUNE"
    const val ACTION_STREAM = "$DADDY_ACTION_PREFIX.STEPDADDY_STREAM"
    const val ACTION_OPEN_EPG = "$DADDY_ACTION_PREFIX.STEPDADDY_EPG"
    const val ACTION_START_HTTP = "$DADDY_ACTION_PREFIX.STEPDADDY_HTTP_START"
    const val ACTION_STOP_HTTP = "$DADDY_ACTION_PREFIX.STEPDADDY_HTTP_STOP"

    private const val ACTION_SETUP_SUFFIX = "STEPDADDY_SETUP"
    private const val ACTION_TUNE_SUFFIX = "STEPDADDY_TUNE"
    private const val ACTION_STREAM_SUFFIX = "STEPDADDY_STREAM"
    private const val ACTION_OPEN_EPG_SUFFIX = "STEPDADDY_EPG"

    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_CHANNEL_ID = "channel_id"
    const val EXTRA_STREAM_URL = "stream_url"
    const val EXTRA_GATEWAY_BASE = "gateway_base"

    const val SCHEME = "stepdaddy"
    const val HOST_SETUP = "setup"
    const val HOST_CHANNEL = "channel"
    const val HOST_STREAM = "stream"
    const val HOST_STATUS = "status"

    const val HTTP_CONTROL_PORT = 4617
    const val HTTP_CONTROL_BASE = "http://127.0.0.1:$HTTP_CONTROL_PORT"
    const val HTTP_CONTROL_STATUS_URL = "$HTTP_CONTROL_BASE/status"
    const val HTTP_CONTROL_STATE_URL = "$HTTP_CONTROL_BASE/state"

    val DEFAULT_GATEWAY_BASE: String
        get() = BuildConfig.DEFAULT_API_URL.trimEnd('/')

    data class PlayerInfo(
        val installed: Boolean,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val likelyActive: Boolean = false,
        val httpControlReachable: Boolean = false,
        val httpControlJson: String? = null,
    )

    data class HttpControlStatus(
        val reachable: Boolean,
        val statusCode: Int? = null,
        val body: String? = null,
    )

    data class StateProbeResult(
        val reachable: Boolean,
        val statusCode: Int? = null,
        val state: TiviMatePlayerState? = null,
        val rawJson: String? = null,
        val error: String? = null,
    )

    data class CommandResult(
        val ok: Boolean,
        val statusCode: Int? = null,
        val body: String? = null,
    )

    fun probe(context: Context): PlayerInfo {
        if (!isInstalled(context)) {
            return PlayerInfo(installed = false)
        }
        val packageInfo = runCatching { loadPackageInfo(context) }.getOrNull()
            ?: return PlayerInfo(installed = false)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val http = probeHttpControl()
        return PlayerInfo(
            installed = true,
            versionName = packageInfo.versionName,
            versionCode = versionCode,
            likelyActive = TiviMateWatch.isTiviMateLikelyActive(context),
            httpControlReachable = http.reachable,
            httpControlJson = http.body,
        )
    }

    fun isInstalled(context: Context): Boolean = playerPackage(context) != null

    /** Package with StepDaddy bridge — prefers daddyliveTV, then legacy DaddyLive, then fleet. */
    fun controlPackage(context: Context): String? {
        if (isPackageInstalled(context, DADDY_LIVE_PACKAGE)) return DADDY_LIVE_PACKAGE
        if (isPackageInstalled(context, LEGACY_DADDY_LIVE_PACKAGE) &&
            hasStepDaddyBridge(context, LEGACY_DADDY_LIVE_PACKAGE)
        ) {
            return LEGACY_DADDY_LIVE_PACKAGE
        }
        if (isPackageInstalled(context, LEGACY_TIVIMATE_PACKAGE) &&
            hasStepDaddyBridge(context, LEGACY_TIVIMATE_PACKAGE)
        ) {
            return LEGACY_TIVIMATE_PACKAGE
        }
        return null
    }

    /** Any installed TiViMate-family player (daddyliveTV, legacy DaddyLive, or stock). */
    fun playerPackage(context: Context): String? {
        if (isPackageInstalled(context, DADDY_LIVE_PACKAGE)) return DADDY_LIVE_PACKAGE
        if (isPackageInstalled(context, LEGACY_DADDY_LIVE_PACKAGE)) return LEGACY_DADDY_LIVE_PACKAGE
        if (isPackageInstalled(context, LEGACY_TIVIMATE_PACKAGE)) return LEGACY_TIVIMATE_PACKAGE
        return null
    }

    /**
     * Probe `:4617/status` and package metadata to distinguish StepDaddy patch vs plain mod vs unknown.
     */
    fun detectInstalledVariant(context: Context): TiviMateVariantProbe {
        val daddyLiveInstalled = isPackageInstalled(context, DADDY_LIVE_PACKAGE)
        val legacyDaddyInstalled = isPackageInstalled(context, LEGACY_DADDY_LIVE_PACKAGE)
        val legacyInstalled = isPackageInstalled(context, LEGACY_TIVIMATE_PACKAGE)
        if (!daddyLiveInstalled && !legacyDaddyInstalled && !legacyInstalled) {
            return TiviMateVariantProbe(TiviMateInstalledVariant.NOT_INSTALLED)
        }

        val statusPatch = probeStatusPatchVersion()
        if (daddyLiveInstalled) {
            val versionName = packageVersionName(context, DADDY_LIVE_PACKAGE)
            if (statusPatch != null) {
                val variant = if (isStepDaddyPatchVersion(statusPatch)) {
                    TiviMateInstalledVariant.STEP_DADDY
                } else {
                    TiviMateInstalledVariant.PLAIN_MOD
                }
                return TiviMateVariantProbe(variant, statusPatch, versionName)
            }
            if (hasStepDaddyBridge(context, DADDY_LIVE_PACKAGE)) {
                return TiviMateVariantProbe(TiviMateInstalledVariant.STEP_DADDY, versionName = versionName)
            }
        }

        if (legacyDaddyInstalled) {
            val versionName = packageVersionName(context, LEGACY_DADDY_LIVE_PACKAGE)
            if (statusPatch != null && !daddyLiveInstalled) {
                val variant = if (isStepDaddyPatchVersion(statusPatch)) {
                    TiviMateInstalledVariant.STEP_DADDY
                } else {
                    TiviMateInstalledVariant.PLAIN_MOD
                }
                return TiviMateVariantProbe(variant, statusPatch, versionName)
            }
            if (hasStepDaddyBridge(context, LEGACY_DADDY_LIVE_PACKAGE)) {
                return TiviMateVariantProbe(TiviMateInstalledVariant.STEP_DADDY, versionName = versionName)
            }
        }

        if (legacyInstalled) {
            val versionName = packageVersionName(context, LEGACY_TIVIMATE_PACKAGE)
            if (statusPatch != null && !daddyLiveInstalled && !legacyDaddyInstalled) {
                val variant = if (isStepDaddyPatchVersion(statusPatch)) {
                    TiviMateInstalledVariant.STEP_DADDY
                } else {
                    TiviMateInstalledVariant.PLAIN_MOD
                }
                return TiviMateVariantProbe(variant, statusPatch, versionName)
            }
            if (hasStepDaddyBridge(context, LEGACY_TIVIMATE_PACKAGE)) {
                return TiviMateVariantProbe(TiviMateInstalledVariant.STEP_DADDY, versionName = versionName)
            }
            if (isLikely461Mod(versionName)) {
                return TiviMateVariantProbe(TiviMateInstalledVariant.PLAIN_MOD, versionName = versionName)
            }
            return TiviMateVariantProbe(TiviMateInstalledVariant.UNKNOWN, versionName = versionName)
        }

        return TiviMateVariantProbe(TiviMateInstalledVariant.UNKNOWN)
    }

    /** Launch TiViMate for gateway use — setup URI when StepDaddy patch is present. */
    fun launchForGateway(context: Context, gatewayBase: String? = null): Boolean {
        if (!isInstalled(context)) return false
        val probe = detectInstalledVariant(context)
        return when (probe.variant) {
            TiviMateInstalledVariant.STEP_DADDY -> {
                val base = normalizeGatewayBase(gatewayBase)
                val stateProbe = probeState(connectTimeoutMs = 1_000, readTimeoutMs = 1_000)
                val needsSetup = stateProbe.reachable &&
                    TiviMatePlaylistStateHelper.needsPlaylistSetup(stateProbe.state)
                val viaSetup = if (needsSetup) triggerSetup(context, base) else false
                val viaMain = launch(context)
                viaSetup || viaMain
            }
            TiviMateInstalledVariant.NOT_INSTALLED -> false
            else -> launch(context)
        }
    }

    fun hasStepDaddyBridge(context: Context, packageName: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME://$HOST_SETUP")).apply {
            setPackage(packageName)
        }
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                probe,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return matches.isNotEmpty()
    }

    private fun probeStatusPatchVersion(
        connectTimeoutMs: Int = 1_500,
        readTimeoutMs: Int = 1_500,
    ): String? {
        val response = probeHttpControl(connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)
        if (!response.reachable) return null
        val body = response.body.orEmpty()
        val status = runCatching {
            stateJson.decodeFromString(TiviMateHttpStatus.serializer(), body)
        }.getOrNull()
        return status?.patchVersion?.takeIf { it.isNotBlank() }
            ?: PATCH_VERSION_JSON.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun isStepDaddyPatchVersion(patchVersion: String): Boolean {
        val normalized = patchVersion.lowercase()
        return normalized.contains("stepdaddy") ||
            normalized.contains("bidir") ||
            normalized.contains("daddy")
    }

    private fun isLikely461Mod(versionName: String?): Boolean {
        val name = versionName?.trim().orEmpty()
        if (name.isEmpty()) return false
        return name.startsWith("4.6.") || name.contains("4.6.1")
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrElse {
            // Fallback when <queries> is missing or package metadata is restricted.
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0).any { it.packageName == packageName }
        }
    }

    private fun packageVersionName(context: Context, packageName: String): String? =
        runCatching { loadPackageInfo(context, packageName).versionName }.getOrNull()

    private fun loadPackageInfo(context: Context, packageName: String = playerPackage(context) ?: PACKAGE) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }

    /** `adb shell am start -n` component for the installed TiViMate-family player. */
    fun launchComponent(context: Context): String {
        val pkg = playerPackage(context) ?: DADDY_LIVE_PACKAGE
        return "$pkg/$MAIN_ACTIVITY_CLASS"
    }

    fun launch(context: Context): Boolean {
        val targetPackage = playerPackage(context)
        if (targetPackage == null) {
            Log.w(TAG, "TiviMate not installed ($DADDY_LIVE_PACKAGE / $LEGACY_TIVIMATE_PACKAGE)")
            return false
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(targetPackage, MAIN_ACTIVITY_CLASS)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "Launched TiViMate via $targetPackage/.ui.MainActivity")
            true
        }.getOrElse { exc ->
            Log.w(TAG, "TiViMate launch failed: ${exc.message}")
            false
        }
    }

    /** Persist boot-tune for next MainActivity resume (patched TiViMate HTTP :4617). */
    fun setBootTuneChannel(
        context: Context,
        channel: Int,
        connectTimeoutMs: Int = 1_500,
        readTimeoutMs: Int = 1_500,
    ): Boolean {
        if (channel <= 0 || !isInstalled(context)) return false
        val response = httpGet(
            "$HTTP_CONTROL_BASE/boot-tune/$channel",
            connectTimeoutMs,
            readTimeoutMs,
        )
        return response.reachable
    }

    /**
     * Triggers StepDaddy playlist auto-setup inside the patched TiViMate build.
     *
     * Uses an explicit broadcast; falls back to `stepdaddy://setup?base=...`.
     */
    fun triggerSetup(context: Context, gatewayBase: String? = null): Boolean {
        val targetPackage = controlPackage(context) ?: return false
        val base = normalizeGatewayBase(gatewayBase)
        val broadcast = sendStepDaddyBroadcast(context, targetPackage, ACTION_SETUP_SUFFIX) {
            putExtra(EXTRA_GATEWAY_BASE, base)
        }
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_SETUP)
            .appendQueryParameter("base", base)
            .build()
        val viaUri = startBridgeUri(context, targetPackage, uri)
        val started = broadcast || viaUri
        if (started) {
            Log.i(TAG, "Triggered TiViMate setup for gateway base=$base")
        }
        return started
    }

    /** Tune to a playlist channel number (tvg-chno / position) in patched TiViMate. */
    fun tuneChannel(context: Context, channelNumber: Int): Boolean {
        val targetPackage = controlPackage(context) ?: return false
        if (channelNumber <= 0) return false
        val broadcast = sendStepDaddyBroadcast(context, targetPackage, ACTION_TUNE_SUFFIX) {
            putExtra(EXTRA_CHANNEL, channelNumber)
        }
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_CHANNEL)
            .appendPath(channelNumber.toString())
            .build()
        val viaUri = startBridgeUri(context, targetPackage, uri)
        val started = broadcast || viaUri
        if (started) {
            Log.i(TAG, "Tuned TiViMate to channel $channelNumber")
        }
        return started
    }

    /**
     * Open a stream URL or tune by channel number.
     *
     * Numeric strings are treated as channel numbers; values containing `://` are stream URLs.
     */
    fun openStream(context: Context, channelOrUrl: String): Boolean {
        val targetPackage = controlPackage(context) ?: return false
        val trimmed = channelOrUrl.trim()
        if (trimmed.isEmpty()) return false

        val asChannel = trimmed.toIntOrNull()
        if (asChannel != null && asChannel > 0 && !trimmed.contains("://")) {
            return tuneChannel(context, asChannel)
        }

        val streamUrl = if (trimmed.contains("://")) {
            trimmed
        } else {
            "${DEFAULT_GATEWAY_BASE}/tivimate-stream/$trimmed.m3u8"
        }

        val broadcast = sendStepDaddyBroadcast(context, targetPackage, ACTION_STREAM_SUFFIX) {
            putExtra(EXTRA_STREAM_URL, streamUrl)
        }
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_STREAM)
            .appendQueryParameter("url", streamUrl)
            .build()
        val viaUri = startBridgeUri(context, targetPackage, uri)
        val started = broadcast || viaUri
        if (started) {
            Log.i(TAG, "Opened TiViMate stream: $streamUrl")
        }
        return started
    }

    /** Open the TiViMate EPG overlay (patched build only). */
    fun openEpg(context: Context): Boolean {
        val targetPackage = controlPackage(context) ?: return false
        val broadcast = sendStepDaddyBroadcast(context, targetPackage, ACTION_OPEN_EPG_SUFFIX)
        val started = broadcast || launch(context)
        if (started) {
            Log.i(TAG, "Requested TiViMate EPG overlay")
        }
        return started
    }

    /** Probe patched TiViMate loopback HTTP control (`GET /status` on port 4617). */
    fun probeHttpControl(
        statusUrl: String = HTTP_CONTROL_STATUS_URL,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): HttpControlStatus = httpGet(statusUrl, connectTimeoutMs, readTimeoutMs)

    /** Probe patched TiViMate player state (`GET /state` on port 4617). */
    fun probeState(
        stateUrl: String = HTTP_CONTROL_STATE_URL,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): StateProbeResult {
        val response = httpGet(stateUrl, connectTimeoutMs, readTimeoutMs)
        if (!response.reachable) {
            return StateProbeResult(
                reachable = false,
                statusCode = response.statusCode,
                error = response.body ?: "tivimate_state_unreachable",
            )
        }
        val body = response.body.orEmpty()
        val state = runCatching {
            stateJson.decodeFromString(TiviMatePlayerState.serializer(), body)
        }.getOrElse { error ->
            Log.w(TAG, "TiViMate /state parse failed: ${error.message}")
            return StateProbeResult(
                reachable = true,
                statusCode = response.statusCode,
                rawJson = body,
                error = "state_parse_failed",
            )
        }
        return StateProbeResult(
            reachable = true,
            statusCode = response.statusCode,
            state = state,
            rawJson = body,
        )
    }

    /** Read buffered TiViMate patch events from the gateway ring buffer. */
    fun getEvents(since: Long? = null): List<TiviMateEvent> =
        TiviMateEventStore.snapshot(since)

    fun channelUp(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): CommandResult = httpCommand("/channel/up", connectTimeoutMs, readTimeoutMs)

    fun channelDown(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): CommandResult = httpCommand("/channel/down", connectTimeoutMs, readTimeoutMs)

    fun pause(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): CommandResult = httpCommand("/pause", connectTimeoutMs, readTimeoutMs)

    fun play(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): CommandResult = httpCommand("/play", connectTimeoutMs, readTimeoutMs)

    fun search(
        name: String,
        connectTimeoutMs: Int = 3_000,
        readTimeoutMs: Int = 3_000,
    ): CommandResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return CommandResult(ok = false, body = "empty_query")
        }
        val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
        return httpCommand("/search?q=$encoded", connectTimeoutMs, readTimeoutMs)
    }

    fun getChannels(
        limit: Int = 50,
        connectTimeoutMs: Int = 3_000,
        readTimeoutMs: Int = 3_000,
    ): TiviMateChannelsResponse {
        val capped = limit.coerceIn(1, 500)
        val response = httpGet(
            "$HTTP_CONTROL_BASE/channels?limit=$capped",
            connectTimeoutMs,
            readTimeoutMs,
        )
        if (!response.reachable) {
            return TiviMateChannelsResponse(
                reachable = false,
                error = response.body ?: "tivimate_channels_unreachable",
            )
        }
        val body = response.body.orEmpty()
        val payload = runCatching {
            stateJson.decodeFromString(TiviMateChannelsPayload.serializer(), body)
        }.getOrElse { error ->
            Log.w(TAG, "TiViMate /channels parse failed: ${error.message}")
            return TiviMateChannelsResponse(
                reachable = true,
                error = "channels_parse_failed",
            )
        }
        return TiviMateChannelsResponse(
            reachable = true,
            channels = payload.channels,
            error = payload.error,
        )
    }

    private fun httpCommand(
        path: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CommandResult {
        val response = httpGet("$HTTP_CONTROL_BASE$path", connectTimeoutMs, readTimeoutMs)
        return CommandResult(
            ok = response.reachable,
            statusCode = response.statusCode,
            body = response.body,
        )
    }

    private fun httpGet(
        url: String,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): HttpControlStatus {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
            }
            try {
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }
                HttpControlStatus(
                    reachable = code in 200..299,
                    statusCode = code,
                    body = body?.takeIf { it.isNotBlank() },
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            Log.d(TAG, "TiViMate HTTP GET failed ($url): ${error.message}")
            HttpControlStatus(reachable = false)
        }
    }

    /**
     * Best-effort stream open on stock TiViMate — bypasses playlist metadata; may not tune EPG/channel numbers.
     */
    fun launchStream(context: Context, streamUrl: String): Boolean {
        val targetPackage = playerPackage(context) ?: return false
        val uri = runCatching { Uri.parse(streamUrl) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            component = ComponentName(targetPackage, MAIN_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "Launched TiViMate stream intent: $streamUrl")
            true
        }.getOrElse { exc ->
            Log.w(TAG, "TiViMate stream launch failed: ${exc.message}")
            false
        }
    }

    private fun normalizeGatewayBase(gatewayBase: String?): String {
        val raw = gatewayBase?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GATEWAY_BASE
        return raw.trimEnd('/')
    }

    private fun broadcastAction(packageName: String, actionSuffix: String): String {
        val prefix = when (packageName) {
            DADDY_LIVE_PACKAGE -> DADDY_ACTION_PREFIX
            LEGACY_DADDY_LIVE_PACKAGE -> LEGACY_DADDY_ACTION_PREFIX
            else -> LEGACY_ACTION_PREFIX
        }
        return "$prefix.$actionSuffix"
    }

    private fun sendStepDaddyBroadcast(
        context: Context,
        packageName: String,
        actionSuffix: String,
        configure: Intent.() -> Unit = {},
    ): Boolean {
        val action = broadcastAction(packageName, actionSuffix)
        val intent = Intent(action).apply {
            setPackage(packageName)
            setComponent(ComponentName(packageName, COMMAND_RECEIVER_CLASS))
            configure()
        }
        return runCatching {
            context.sendBroadcast(intent)
            true
        }.getOrElse { exc ->
            Log.w(TAG, "TiViMate broadcast $action failed: ${exc.message}")
            false
        }
    }

    private fun startBridgeUri(context: Context, packageName: String, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            setComponent(ComponentName(packageName, BRIDGE_ACTIVITY_CLASS))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { exc ->
            Log.w(TAG, "TiViMate bridge URI failed ($uri): ${exc.message}")
            false
        }
    }

    private val stateJson = Json { ignoreUnknownKeys = true }

    private val PATCH_VERSION_JSON = Regex(""""patchVersion"\s*:\s*"([^"]+)"""")

    private const val TAG = "TiviMateController"
}
