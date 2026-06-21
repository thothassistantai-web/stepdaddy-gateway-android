package com.thothassistant.stepdaddy.gateway.admin

import com.thothassistant.stepdaddy.gateway.model.AdminActionResult
import com.thothassistant.stepdaddy.gateway.model.AdminChannelSearchResult
import com.thothassistant.stepdaddy.gateway.model.AdminChannelSummary
import com.thothassistant.stepdaddy.gateway.model.AdminDiscovery
import com.thothassistant.stepdaddy.gateway.model.AdminImportResult
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatch
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsSnapshot
import com.thothassistant.stepdaddy.gateway.model.AssetExportResult
import com.thothassistant.stepdaddy.gateway.model.CategoryAuditResult
import com.thothassistant.stepdaddy.gateway.model.ResolveEpgResult
import com.thothassistant.stepdaddy.gateway.model.ResolveLogoResult
import com.thothassistant.stepdaddy.gateway.model.ResolveStreamResult

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

    fun stopGateway(): AdminActionResult
    fun restartGateway(scope: String = "http"): AdminActionResult

    fun searchChannels(query: String, limit: Int = 50): AdminChannelSearchResult
    fun getChannel(channelId: String): AdminChannelSummary?

    fun setLogoOverride(channelName: String, url: String): AdminActionResult
    fun clearLogoOverride(channelName: String): AdminActionResult
    fun setEpgNameOverride(channelName: String, tvgId: String): AdminActionResult
    fun setEpgIdOverride(channelId: String, tvgId: String): AdminActionResult

    fun setCategoryOverride(channelId: String?, channelName: String?, groupTitle: String): AdminActionResult
    fun clearCategoryOverride(channelId: String?, channelName: String?): AdminActionResult
    fun moveCategories(channelIds: List<String>, groupTitle: String): AdminActionResult
    fun categoryAudit(limit: Int = 200, groupFilter: String? = null): CategoryAuditResult

    fun exportAssets(type: String, layer: String = "merged"): AssetExportResult
    fun importAssets(type: String, entries: Map<String, String>, merge: Boolean = true): AdminImportResult
    fun importEpgCsv(csv: String): AdminImportResult
    fun clearRuntimeAssets(type: String): AdminActionResult

    fun resolveLogo(channelName: String, tvgId: String?): ResolveLogoResult
    fun resolveEpg(channelName: String): ResolveEpgResult
    suspend fun resolveStream(channelId: String, probe: Boolean = false): ResolveStreamResult
}
