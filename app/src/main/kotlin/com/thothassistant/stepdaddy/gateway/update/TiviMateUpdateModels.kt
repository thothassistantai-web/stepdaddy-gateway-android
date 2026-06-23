package com.thothassistant.stepdaddy.gateway.update

import kotlinx.serialization.Serializable

@Serializable
data class TiviMateUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val baseTiviMateVersion: String? = null,
    val apkUrl: String,
    val apkFileName: String? = null,
    val stableApkPath: String? = null,
    val releaseNotes: String? = null,
    val mandatory: Boolean = false,
)

data class TiviMateUpdateInfo(
    val manifest: TiviMateUpdateManifest,
    val releaseTag: String,
    val releasePageUrl: String,
    val sourceLabel: String,
)
