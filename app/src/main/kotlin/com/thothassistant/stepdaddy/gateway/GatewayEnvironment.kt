package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.content.SharedPreferences
import com.thothassistant.stepdaddy.gateway.epg.EpgConfig
import com.thothassistant.stepdaddy.gateway.network.NetworkAccessMode
import com.thothassistant.stepdaddy.gateway.upstream.PlaylistTitleStyle
import com.thothassistant.stepdaddy.gateway.upstream.SupplementImportMode
import java.security.SecureRandom
import java.util.Base64

class GatewayEnvironment(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, BuildConfig.DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    /** Network access enforcement mode for the embedded HTTP server. */
    var networkAccessMode: NetworkAccessMode
        get() = NetworkAccessMode.fromPref(prefs.getString(KEY_NETWORK_ACCESS_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_NETWORK_ACCESS_MODE, value.name).apply()
        }

    /** Friendly label shown on the dashboard and in LAN discovery banners. */
    var gatewayName: String
        get() = prefs.getString(KEY_GATEWAY_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_GATEWAY_NAME, value.trim()).apply()
        }

    /**
     * HTTPS tunnel base URL for Remote mode (Cloudflare Tunnel, Tailscale funnel, etc.).
     * Does not include a trailing path segment.
     */
    var remoteGatewayUrl: String
        get() = prefs.getString(KEY_REMOTE_GATEWAY_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_REMOTE_GATEWAY_URL, value.trim().trimEnd('/')).apply()
        }

    /** Bearer token required for non-LAN clients when [networkAccessMode] is REMOTE. */
    var remoteAccessToken: String
        get() = prefs.getString(KEY_REMOTE_ACCESS_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_REMOTE_ACCESS_TOKEN, value.trim()).apply()
        }

    fun ensureRemoteAccessToken(): String {
        val current = remoteAccessToken
        if (current.isNotBlank()) return current
        val token = generateAccessToken()
        remoteAccessToken = token
        return token
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, BuildConfig.DEFAULT_API_URL) ?: BuildConfig.DEFAULT_API_URL
        set(value) {
            prefs.edit().putString(KEY_API_URL, value.trimEnd('/')).apply()
        }

    var dlhdBaseUrl: String
        get() = prefs.getString(KEY_DLHD_BASE_URL, BuildConfig.DEFAULT_DLHD_BASE_URL)
            ?: BuildConfig.DEFAULT_DLHD_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_DLHD_BASE_URL, value.trimEnd('/')).apply()
        }

    var mirrorUrls: List<String>
        get() {
            val raw = prefs.getString(KEY_MIRROR_URLS, DEFAULT_MIRRORS_CSV) ?: DEFAULT_MIRRORS_CSV
            return raw.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        }
        set(value) {
            prefs.edit().putString(KEY_MIRROR_URLS, value.joinToString(",")).apply()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()
        }

    /** When true, opening MainActivity starts the gateway if it is not already running. */
    var autoStartOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_LAUNCH, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START_ON_LAUNCH, value).apply()
        }

    /** When true, launch TiviMate once the gateway is ready (after channel load). */
    var launchTivimateOnReady: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_TIVIMATE_ON_READY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LAUNCH_TIVIMATE_ON_READY, value).apply()
        }

    /** When true, periodic + wake kicks prioritize recovery while TiviMate is active. */
    var tivimateWatchEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIVIMATE_WATCH, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TIVIMATE_WATCH, value).apply()
        }

    /**
     * TiviMate playlist display names: [PlaylistTitleStyle.XTREAM_CATEGORY] uses `US: NAME HD`
     * with category [GroupTitleResolver] groups; [PlaylistTitleStyle.LEGACY] uses flag suffixes.
     */
    var playlistTitleStyle: PlaylistTitleStyle
        get() = PlaylistTitleStyle.fromPref(prefs.getString(KEY_PLAYLIST_TITLE_STYLE, null))
        set(value) {
            prefs.edit().putString(KEY_PLAYLIST_TITLE_STYLE, value.name).apply()
        }

    var serverRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVER_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SERVER_RUNNING, value).apply()
        }

    /**
     * When true, the gateway APK runs a minimal MoveOnJoy sidecar on loopback :4124.
     */
    var embeddedSidecarEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMBEDDED_SIDECAR_ENABLED, BuildConfig.DEFAULT_EMBEDDED_SIDECAR_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_EMBEDDED_SIDECAR_ENABLED, value).apply()
        }

    /**
     * Optional LAN URL for TVApp2-style supplement (e.g. http://192.168.1.50:4124).
     * Empty disables sidecar fetch; MoveOnJoy-only when set. TheTvApp linear entries are filtered out.
     */
    var supplementBaseUrl: String
        get() = prefs.getString(KEY_SUPPLEMENT_BASE_URL, BuildConfig.DEFAULT_SUPPLEMENT_BASE_URL)
            ?: BuildConfig.DEFAULT_SUPPLEMENT_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_SUPPLEMENT_BASE_URL, value.trim().trimEnd('/')).apply()
        }

    /**
     * When true, merges Special Events from DaddyLive schedule (tv.json/tv2.json) and TheTvApp embeds.
     * Off by default.
     */
    var supplementSportsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPPLEMENT_SPORTS_ENABLED, BuildConfig.DEFAULT_SUPPLEMENT_SPORTS_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPLEMENT_SPORTS_ENABLED, value).apply()
        }

    /**
     * When true, merges UK/US iptv-org stream playlists from GitHub (uk.m3u … us_xumo.m3u).
     * Refreshed on each supplement sync from upstream raw URLs.
     */
    var supplementIptvOrgEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPPLEMENT_IPTV_ORG_ENABLED, BuildConfig.DEFAULT_SUPPLEMENT_IPTV_ORG_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPLEMENT_IPTV_ORG_ENABLED, value).apply()
        }

    /**
     * When true, merges 24/7 channels from ntv.cx (Titan CDN + Falcon; HLS resolved on each play).
     * Enabled by default on fresh installs.
     */
    var supplementNtvCxEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPPLEMENT_NTV_CX_ENABLED, BuildConfig.DEFAULT_SUPPLEMENT_NTV_CX_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPLEMENT_NTV_CX_ENABLED, value).apply()
        }

    /**
     * When true, merges Adult Swim 24/7 marathon streams (Turner CDN; probed at sync).
     */
    var supplementAdultSwimEnabled: Boolean
        get() = prefs.getBoolean(
            KEY_SUPPLEMENT_ADULT_SWIM_ENABLED,
            BuildConfig.DEFAULT_SUPPLEMENT_ADULT_SWIM_ENABLED,
        )
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPLEMENT_ADULT_SWIM_ENABLED, value).apply()
        }

    /**
     * How Adult Swim marathon rows are merged.
     */
    var supplementAdultSwimImportMode: SupplementImportMode
        get() = SupplementImportMode.fromPref(
            prefs.getString(KEY_SUPPLEMENT_ADULT_SWIM_IMPORT_MODE, null),
        )
        set(value) {
            prefs.edit().putString(KEY_SUPPLEMENT_ADULT_SWIM_IMPORT_MODE, value.name).apply()
        }

    /**
     * How MoveOnJoy sidecar channels are merged.
     * [SupplementImportMode.FULL_CATALOG] imports the full sidecar catalog (default).
     */
    var supplementSidecarImportMode: SupplementImportMode
        get() = SupplementImportMode.fromPref(
            prefs.getString(KEY_SUPPLEMENT_SIDECAR_IMPORT_MODE, null),
        )
        set(value) {
            prefs.edit().putString(KEY_SUPPLEMENT_SIDECAR_IMPORT_MODE, value.name).apply()
        }

    /**
     * How iptv-org FAST playlists are merged.
     * [SupplementImportMode.FULL_CATALOG] imports every playlist row (default).
     */
    var supplementIptvOrgImportMode: SupplementImportMode
        get() = SupplementImportMode.fromPref(
            prefs.getString(KEY_SUPPLEMENT_IPTV_ORG_IMPORT_MODE, null),
        )
        set(value) {
            prefs.edit().putString(KEY_SUPPLEMENT_IPTV_ORG_IMPORT_MODE, value.name).apply()
        }

    /**
     * [SupplementImportMode.FULL_CATALOG] includes every 24/7 row (default).
     * [SupplementImportMode.SKIP_DUPLICATES] skips names already on the main DaddyLive list.
     */
    var supplementNtvCxImportMode: SupplementImportMode
        get() {
            val raw = prefs.getString(KEY_SUPPLEMENT_NTV_CX_MERGE_MODE, null)
            return if (raw != null) {
                SupplementImportMode.fromPref(raw)
            } else {
                SupplementImportMode.fromSkipDuplicatesPref(
                    BuildConfig.DEFAULT_SUPPLEMENT_NTV_CX_SUPPLEMENT_ONLY,
                )
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SUPPLEMENT_NTV_CX_MERGE_MODE, value.name).apply()
        }

    /** @deprecated use [supplementNtvCxImportMode] */
    var supplementNtvCxMergeMode: SupplementImportMode
        get() = supplementNtvCxImportMode
        set(value) {
            supplementNtvCxImportMode = value
        }

    /**
     * When true, the gateway builds and serves merged XMLTV at `/epg.xml`.
     * When false, EPG build/download is skipped and TiviMate uses [externalEpgUrl] from the playlist.
     */
    var gatewayEpgEnabled: Boolean
        get() = prefs.getBoolean(KEY_GATEWAY_EPG_ENABLED, BuildConfig.DEFAULT_GATEWAY_EPG_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_GATEWAY_EPG_ENABLED, value).apply()
        }

    /**
     * XMLTV feed URL(s) for TiviMate when [gatewayEpgEnabled] is false.
     * Stored as comma/newline-separated text; defaults to [EpgConfig.DEFAULT_EXTERNAL_EPG_URLS].
     */
    var externalEpgUrl: String
        get() {
            if (!prefs.contains(KEY_EXTERNAL_EPG_URL)) {
                return BuildConfig.DEFAULT_EXTERNAL_EPG_URL
            }
            return prefs.getString(KEY_EXTERNAL_EPG_URL, "").orEmpty()
        }
        set(value) {
            prefs.edit().putString(KEY_EXTERNAL_EPG_URL, value.trim()).apply()
        }

    fun externalEpgUrls(): List<String> {
        val parsed = EpgConfig.parseExternalEpgUrls(externalEpgUrl)
        return parsed.ifEmpty { EpgConfig.DEFAULT_EXTERNAL_EPG_URLS }
    }

    fun externalEpgUrlForDisplay(): String =
        EpgConfig.formatExternalEpgUrlsForDisplay(externalEpgUrls())

    /** When true, merge iptv-org FAST provider EPG (Pluto, Plex, Xumo, Distro) for supplement channels. */
    var iptvOrgEpgEnabled: Boolean
        get() = prefs.getBoolean(KEY_IPTV_ORG_EPG_ENABLED, BuildConfig.DEFAULT_IPTV_ORG_EPG_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_IPTV_ORG_EPG_ENABLED, value).apply()
        }

    /**
     * URL to merged gzip XMLTV for iptv-org FAST guides. Empty uses bundled asset only.
     * Generate via scripts/grab-iptv-org-fast-epg.sh and host on GitHub raw or LAN.
     */
    var iptvOrgEpgUrl: String
        get() = prefs.getString(KEY_IPTV_ORG_EPG_URL, BuildConfig.DEFAULT_IPTV_ORG_EPG_URL)
            ?: BuildConfig.DEFAULT_IPTV_ORG_EPG_URL
        set(value) {
            prefs.edit().putString(KEY_IPTV_ORG_EPG_URL, value.trim()).apply()
        }

    /** When true, check for app updates when the dashboard opens. */
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, BuildConfig.DEFAULT_AUTO_CHECK_UPDATES)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()
        }

    /** When true, download available updates automatically in the background. */
    var autoDownloadUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_UPDATES, BuildConfig.DEFAULT_AUTO_DOWNLOAD_UPDATES)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_UPDATES, value).apply()
        }

    /**
     * User override for the update manifest URL. Empty when using the built-in channel.
     */
    val updateManifestUrlOverride: String
        get() = prefs.getString(KEY_UPDATE_MANIFEST_URL, "").orEmpty()

    /**
     * Effective manifest URL: user override when set, otherwise [BuildConfig.DEFAULT_UPDATE_MANIFEST_URL].
     * Does not persist the default into prefs on read.
     */
    val updateManifestUrl: String
        get() = updateManifestUrlOverride.trim().ifBlank { BuildConfig.DEFAULT_UPDATE_MANIFEST_URL }

    fun setUpdateManifestUrlOverride(value: String) {
        prefs.edit().putString(KEY_UPDATE_MANIFEST_URL, value.trim()).apply()
    }

    /**
     * Google Drive folder URL placeholder for future update channel support.
     * When set, app tries {folder}/update-manifest.json as a fallback source.
     */
    var updateDriveFolderUrl: String
        get() = prefs.getString(KEY_UPDATE_DRIVE_FOLDER_URL, BuildConfig.DEFAULT_UPDATE_DRIVE_FOLDER_URL)
            ?: BuildConfig.DEFAULT_UPDATE_DRIVE_FOLDER_URL
        set(value) {
            prefs.edit().putString(KEY_UPDATE_DRIVE_FOLDER_URL, value.trim()).apply()
        }

    /** Optional versionCode the user dismissed for an optional update prompt. */
    var dismissedUpdateVersionCode: Int
        get() = prefs.getInt(KEY_DISMISSED_UPDATE_VERSION_CODE, 0)
        set(value) {
            prefs.edit().putInt(KEY_DISMISSED_UPDATE_VERSION_CODE, value).apply()
        }

    /** Cached path to a downloaded self-update APK awaiting install. */
    var pendingUpdateApkPath: String
        get() = prefs.getString(KEY_PENDING_UPDATE_APK_PATH, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PENDING_UPDATE_APK_PATH, value).apply()
        }

    /** Survives process death within the same boot session. Cleared on BOOT_COMPLETED. */
    var readyBannerShownThisBoot: Boolean
        get() = prefs.getBoolean(KEY_READY_BANNER_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_READY_BANNER_SHOWN, value).apply()
        }

    fun clearReadyBannerForNewBoot() {
        prefs.edit().putBoolean(KEY_READY_BANNER_SHOWN, false).apply()
    }

    /** Clears persisted run state from a prior session — prefs survive reboot. */
    fun clearBootStaleState() {
        prefs.edit().putBoolean(KEY_SERVER_RUNNING, false).apply()
    }

    /** True when the service restarted within [CRASH_RECOVERY_BANNER_SKIP_MS] of a prior start. */
    fun isRecentCrashRecovery(): Boolean {
        val lastStart = prefs.getLong(KEY_LAST_SERVICE_START_MS, 0L)
        if (lastStart <= 0L) return false
        return System.currentTimeMillis() - lastStart < CRASH_RECOVERY_BANNER_SKIP_MS
    }

    val lastServiceStartMs: Long
        get() = prefs.getLong(KEY_LAST_SERVICE_START_MS, 0L)

    fun recordServiceStart() {
        prefs.edit().putLong(KEY_LAST_SERVICE_START_MS, System.currentTimeMillis()).apply()
    }

    fun loopbackBase(): String = "http://127.0.0.1:$port"

    fun displayGatewayName(): String =
        gatewayName.trim().ifBlank { "StepDaddy Gateway" }

    private fun generateAccessToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Point supplement fetch at the embedded loopback sidecar when enabled. */
    fun ensureEmbeddedSidecarUrl() {
        if (!embeddedSidecarEnabled) return
        val local = com.thothassistant.stepdaddy.gateway.sidecar.SidecarConfig.LOOPBACK_BASE
        val current = supplementBaseUrl.trim()
        if (current.isBlank() || isRemoteSidecarUrl(current)) {
            supplementBaseUrl = local
        }
    }

    private fun isRemoteSidecarUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(":4124") &&
            !lower.contains("127.0.0.1") &&
            !lower.contains("localhost")
    }

    companion object {
        private const val PREFS_NAME = "stepdaddy_gateway"
        private const val KEY_PORT = "port"
        private const val KEY_NETWORK_ACCESS_MODE = "network_access_mode"
        private const val KEY_GATEWAY_NAME = "gateway_name"
        private const val KEY_REMOTE_GATEWAY_URL = "remote_gateway_url"
        private const val KEY_REMOTE_ACCESS_TOKEN = "remote_access_token"
        private const val KEY_API_URL = "api_url"
        private const val KEY_DLHD_BASE_URL = "dlhd_base_url"
        private const val KEY_MIRROR_URLS = "mirror_urls"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_AUTO_START_ON_LAUNCH = "auto_start_on_launch"
        private const val KEY_LAUNCH_TIVIMATE_ON_READY = "launch_tivimate_on_ready"
        private const val KEY_TIVIMATE_WATCH = "tivimate_watch"
        private const val KEY_PLAYLIST_TITLE_STYLE = "playlist_title_style"
        private const val KEY_SERVER_RUNNING = "server_running"
        private const val KEY_READY_BANNER_SHOWN = "ready_banner_shown"
        private const val KEY_LAST_SERVICE_START_MS = "last_service_start_ms"
        private const val KEY_SUPPLEMENT_BASE_URL = "supplement_base_url"
        private const val KEY_EMBEDDED_SIDECAR_ENABLED = "embedded_sidecar_enabled"
        private const val KEY_SUPPLEMENT_SPORTS_ENABLED = "supplement_sports_enabled"
        private const val KEY_SUPPLEMENT_IPTV_ORG_ENABLED = "supplement_iptv_org_enabled"
        private const val KEY_SUPPLEMENT_NTV_CX_ENABLED = "supplement_ntv_cx_enabled"
        private const val KEY_SUPPLEMENT_ADULT_SWIM_ENABLED = "supplement_adult_swim_enabled"
        private const val KEY_SUPPLEMENT_ADULT_SWIM_IMPORT_MODE = "supplement_adult_swim_import_mode"
        private const val KEY_SUPPLEMENT_SIDECAR_IMPORT_MODE = "supplement_sidecar_import_mode"
        private const val KEY_SUPPLEMENT_IPTV_ORG_IMPORT_MODE = "supplement_iptv_org_import_mode"
        private const val KEY_SUPPLEMENT_NTV_CX_MERGE_MODE = "supplement_ntv_cx_merge_mode"
        private const val KEY_GATEWAY_EPG_ENABLED = "gateway_epg_enabled"
        private const val KEY_EXTERNAL_EPG_URL = "external_epg_url"
        private const val KEY_IPTV_ORG_EPG_ENABLED = "iptv_org_epg_enabled"
        private const val KEY_IPTV_ORG_EPG_URL = "iptv_org_epg_url"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_AUTO_DOWNLOAD_UPDATES = "auto_download_updates"
        private const val KEY_UPDATE_MANIFEST_URL = "update_manifest_url"
        private const val KEY_UPDATE_DRIVE_FOLDER_URL = "update_drive_folder_url"
        private const val KEY_DISMISSED_UPDATE_VERSION_CODE = "dismissed_update_version_code"
        private const val KEY_PENDING_UPDATE_APK_PATH = "pending_update_apk_path"
        private const val CRASH_RECOVERY_BANNER_SKIP_MS = 10 * 60 * 1000L
        private const val DEFAULT_MIRRORS_CSV =
            "https://daddylive.org,https://daddylive.li,https://daddylive.eu"
    }
}
