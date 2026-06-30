package com.thothassistant.stepdaddy.gateway.update

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.getText
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AppUpdateRepository(
    private val environment: GatewayEnvironment,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetchUpdate(): AppUpdateInfo? {
        val manifestUrl = resolveManifestUrl()
        if (manifestUrl.isNotEmpty()) {
            runCatching { fetchFromUrl(manifestUrl, "manifest") }.getOrNull()?.let { return it }
        }
        val driveUrl = environment.updateDriveFolderUrl.trim()
        if (driveUrl.isNotEmpty()) {
            // TODO(next release): Google Drive API — list folder, resolve latest APK + manifest
            // when folder is not world-readable. Today we only support a public static URL.
            runCatching { fetchFromUrl(resolveDriveManifestUrl(driveUrl), "drive") }.getOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun fetchFromUrl(url: String, sourceLabel: String): AppUpdateInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val text = httpClient.getText(request)
        val manifest = if (isGitHubReleasesUrl(url)) {
            parseGitHubRelease(text) ?: return null
        } else {
            json.decodeFromString(UpdateManifest.serializer(), text)
        }
        return manifest
            ?.forCurrentBuild(isDebugBuild())
            ?.takeIf { it.apkUrl.isNotBlank() }
            ?.let { AppUpdateInfo(it, sourceLabel) }
    }

    private suspend fun parseGitHubRelease(text: String): UpdateManifest? {
        val release = json.decodeFromString(GitHubRelease.serializer(), text)
        val manifestAsset = release.assets.firstOrNull { it.name.equals(MANIFEST_ASSET_NAME, ignoreCase = true) }
        if (manifestAsset != null) {
            val manifestRequest = Request.Builder()
                .url(manifestAsset.browserDownloadUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val manifestText = runCatching { httpClient.getText(manifestRequest) }.getOrNull().orEmpty()
            if (manifestText.isNotBlank()) {
                val manifest = json.decodeFromString(UpdateManifest.serializer(), manifestText)
                return manifest.forCurrentBuild(isDebugBuild())
            }
        }
        val apkAsset = selectApkAsset(release.assets) ?: return null
        val versionName = release.tagName.removePrefix("v").ifBlank { release.name.orEmpty() }
        val versionCode = parseVersionCodeFromBody(release.body)
            ?: parseVersionCodeFromTag(release.tagName)
            ?: return null
        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName.ifBlank { versionCode.toString() },
            apkUrl = apkAsset.browserDownloadUrl,
            releaseNotes = release.body?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseVersionCodeFromBody(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        return VERSION_CODE_BODY_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseVersionCodeFromTag(tag: String): Int? =
        VERSION_CODE_TAG_REGEX.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun isGitHubReleasesUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("api.github.com") && lower.contains("/releases")
    }

    private fun isDebugBuild(): Boolean = BuildConfig.APPLICATION_ID.endsWith(".debug")

    private fun selectApkAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        if (isDebugBuild()) {
            return assets.firstOrNull { it.name.endsWith("-debug.apk", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        }
        return assets.firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
            ?: assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) &&
                    !it.name.endsWith("-debug.apk", ignoreCase = true)
            }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    private fun resolveManifestUrl(): String = environment.updateManifestUrl.trim()

    private fun resolveDriveManifestUrl(folderUrl: String): String {
        // Stub: expects a public folder with update-manifest.json at the root.
        // Drive API integration will replace direct URL guessing — see docs/UPDATES.md.
        val trimmed = folderUrl.trim().trimEnd('/')
        return if (trimmed.endsWith(".json", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/update-manifest.json"
        }
    }

    companion object {
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val MANIFEST_ASSET_NAME = "update-manifest.json"
        private val VERSION_CODE_BODY_REGEX = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val VERSION_CODE_TAG_REGEX = Regex("""(?:\+|\()(\d+)\)?$""")

        fun releasesPageUrl(): String {
            val repo = BuildConfig.GATEWAY_GITHUB_RELEASE_REPO.trim()
            return "https://github.com/$repo/releases"
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
