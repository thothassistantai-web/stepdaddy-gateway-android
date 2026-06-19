package com.thothassistant.stepdaddy.gateway.install

import kotlinx.serialization.Serializable

@Serializable
data class InstallAppsCatalog(
    val version: Int = 1,
    val apps: List<InstallAppEntry> = emptyList(),
)

@Serializable
data class InstallAppEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val iconUrl: String? = null,
    val apkUrl: String,
    val source: String,
    val packageName: String? = null,
    val version: String? = null,
)

enum class InstallAppState {
    IDLE,
    DOWNLOADING,
    INSTALLING,
    DONE,
    FAILED,
}

data class InstallAppUiItem(
    val entry: InstallAppEntry,
    val state: InstallAppState = InstallAppState.IDLE,
    val progressPercent: Int = 0,
    val statusText: String = "",
    val installedVersion: String? = null,
    val selected: Boolean = false,
)
