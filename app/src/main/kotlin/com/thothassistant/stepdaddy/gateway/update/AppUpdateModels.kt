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
    fun forCurrentBuild(isDebugBuild: Boolean): UpdateManifest {
        if (!isDebugBuild) return this
        val debugUrl = apkUrlDebug?.trim().orEmpty()
        if (debugUrl.isBlank() || debugUrl == apkUrl) return this
        return copy(apkUrl = debugUrl)
    }
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
