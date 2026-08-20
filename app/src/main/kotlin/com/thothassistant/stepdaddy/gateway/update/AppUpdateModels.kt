package com.thothassistant.stepdaddy.gateway.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int? = null,
    /** Stable / release package OTA URL (`com.thothassistant.stepdaddy.gateway`). */
    val apkUrl: String,
    /** Debug package OTA URL (`com.thothassistant.stepdaddy.gateway.debug`). */
    val apkUrlDebug: String? = null,
    /** Optional SHA-256 checksum for `apkUrl`. */
    val apkSha256: String? = null,
    /** Optional SHA-256 checksum for `apkUrlDebug`. */
    val apkSha256Debug: String? = null,
    val releaseNotes: String? = null,
    val mandatory: Boolean = false,
) {
    /**
     * Returns this manifest if the current build has a usable APK URL.
     * Does **not** overwrite [apkUrl] with the debug URL — both channels stay available
     * so debug builds can still graduate to the release package.
     */
    fun forCurrentBuild(isDebugBuild: Boolean): UpdateManifest? {
        val url = resolvedApkUrl(isDebugBuild)
        return takeIf { url.isNotBlank() }
    }

    /** OTA URL for the currently installed applicationId (debug→debug, release→release). */
    fun resolvedApkUrl(isDebugBuild: Boolean): String {
        if (!isDebugBuild) return apkUrl.trim()
        val debugUrl = apkUrlDebug?.trim().orEmpty()
        return debugUrl.ifBlank { apkUrl.trim() }
    }

    fun resolvedApkSha256(isDebugBuild: Boolean): String? {
        if (!isDebugBuild) return apkSha256?.trim()?.takeIf { it.isNotEmpty() }
        return apkSha256Debug?.trim()?.takeIf { it.isNotEmpty() }
            ?: apkSha256?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Release package URL used by “Graduate to Release” from a debug install. */
    fun releaseApkUrl(): String = apkUrl.trim()
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

data class AppUpdateInfo(
    val manifest: UpdateManifest,
    val sourceLabel: String,
)
