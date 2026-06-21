package com.thothassistant.stepdaddy.gateway.admin

import com.thothassistant.stepdaddy.gateway.model.AdminActionResult
import com.thothassistant.stepdaddy.gateway.model.AdminChannelSearchResult
import com.thothassistant.stepdaddy.gateway.model.AdminDiscovery
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatch
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsSnapshot
import com.thothassistant.stepdaddy.gateway.model.ResolveEpgResult
import com.thothassistant.stepdaddy.gateway.model.ResolveLogoResult

/** Runtime control surface for the embedded gateway (HTTP admin API + CLI). */
interface GatewayAdminActions {
    fun discovery(): AdminDiscovery

    fun getSettings(): AdminSettingsSnapshot

    fun patchSettings(patch: AdminSettingsPatch): Pair<AdminSettingsSnapshot, Boolean>

    suspend fun refreshChannels(force: Boolean = true): AdminActionResult

    suspend fun refreshSupplements(force: Boolean = true): AdminActionResult

    suspend fun refreshEpg(force: Boolean = true): AdminActionResult

    suspend fun refreshLogos(): AdminActionResult

    suspend fun refreshTvgIds(): AdminActionResult

    fun prewarmPlaylist(): AdminActionResult

    fun searchChannels(query: String, limit: Int = 50): AdminChannelSearchResult

    fun setLogoOverride(channelName: String, url: String): AdminActionResult

    fun clearLogoOverride(channelName: String): AdminActionResult

    fun setEpgNameOverride(channelName: String, tvgId: String): AdminActionResult

    fun setEpgIdOverride(channelId: String, tvgId: String): AdminActionResult

    fun resolveLogo(channelName: String, tvgId: String?): ResolveLogoResult

    fun resolveEpg(channelName: String): ResolveEpgResult
}
