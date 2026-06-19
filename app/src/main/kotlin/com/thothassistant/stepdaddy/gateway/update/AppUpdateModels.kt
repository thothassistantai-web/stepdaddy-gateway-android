package com.thothassistant.stepdaddy.gateway.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int? = null,
    val apkUrl: String,
    val releaseNotes: String? = null,
    val mandatory: Boolean = false,
)

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
