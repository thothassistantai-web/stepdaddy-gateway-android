package com.thothassistant.stepdaddy.gateway.admin

import android.content.Context
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.EpgManager
import com.thothassistant.stepdaddy.gateway.model.AdminActionResult
import com.thothassistant.stepdaddy.gateway.model.AdminChannelSearchResult
import com.thothassistant.stepdaddy.gateway.model.AdminChannelSummary
import com.thothassistant.stepdaddy.gateway.model.AdminDiscovery
import com.thothassistant.stepdaddy.gateway.model.AdminEndpoint
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsPatch
import com.thothassistant.stepdaddy.gateway.model.AdminSettingsSnapshot
import com.thothassistant.stepdaddy.gateway.model.ResolveEpgResult
import com.thothassistant.stepdaddy.gateway.model.ResolveLogoResult
import com.thothassistant.stepdaddy.gateway.network.NetworkAccessMode
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.CategoryOverrideStore
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.FreeTvIptvConfig
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.model.AdminImportResult
import com.thothassistant.stepdaddy.gateway.model.AssetExportResult
import com.thothassistant.stepdaddy.gateway.model.CategoryAuditEntry
import com.thothassistant.stepdaddy.gateway.model.CategoryAuditResult
import com.thothassistant.stepdaddy.gateway.model.ResolveStreamResult
import com.thothassistant.stepdaddy.gateway.model.StreamProbeResult
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import kotlinx.coroutines.CompletableDeferred
import com.thothassistant.stepdaddy.gateway.upstream.PremiumMovieChannelMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class GatewayAdminController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val app: GatewayApp,
    private val logoResolver: LogoResolver,
    private val prewarmPlaylist: () -> Unit,
    private val stopGatewayAction: () -> Unit,
    private val restartHttpAction: () -> Unit,
    private val restartFullAction: () -> Unit,
) : GatewayAdminActions {

    private val assetManager = AdminAssetManager(context)

    override fun discovery(): AdminDiscovery = AdminDiscovery(
        version = BuildConfig.VERSION_NAME,
        baseUrl = environment.loopbackBase(),
        endpoints = listOf(
            AdminEndpoint("GET", "/health", "Full gateway health and EPG coverage"),
            AdminEndpoint("GET", "/api/v1", "This discovery document"),
            AdminEndpoint("GET", "/api/v1/settings", "Read gateway settings"),
            AdminEndpoint("PATCH", "/api/v1/settings", "Update gateway settings (partial JSON body)"),
            AdminEndpoint("GET", "/api/v1/channels?q=", "Search playlist channels by name"),
            AdminEndpoint("POST", "/api/v1/actions/refresh-channels", "Reload DaddyLive channel list"),
            AdminEndpoint("POST", "/api/v1/actions/refresh-supplements", "Sync supplement sources"),
            AdminEndpoint("POST", "/api/v1/actions/refresh-epg", "Rebuild EPG XML"),
            AdminEndpoint("POST", "/api/v1/actions/refresh-logos", "Run logo backfill pass"),
            AdminEndpoint("POST", "/api/v1/actions/refresh-tvg-ids", "Backfill missing tvg-ids"),
            AdminEndpoint("POST", "/api/v1/actions/prewarm-playlist", "Rebuild playlist cache"),
            AdminEndpoint("POST", "/api/v1/overrides/logo", "Set runtime logo override"),
            AdminEndpoint("DELETE", "/api/v1/overrides/logo?channelName=", "Remove runtime logo override"),
            AdminEndpoint("POST", "/api/v1/overrides/epg-name", "Set runtime EPG name → tvg-id override"),
            AdminEndpoint("POST", "/api/v1/overrides/epg-id", "Set runtime EPG channel-id → tvg-id override"),
            AdminEndpoint("GET", "/api/v1/resolve/logo?channelName=", "Probe logo lookup"),
            AdminEndpoint("GET", "/api/v1/resolve/epg?channelName=", "Probe EPG tvg-id lookup"),
            AdminEndpoint("GET", "/api/v1/resolve/stream?channelId=", "Play URL + optional stream probe"),
            AdminEndpoint("GET", "/api/v1/channels/{id}", "Lookup channel by id"),
            AdminEndpoint("POST", "/api/v1/actions/stop", "Stop gateway foreground service"),
            AdminEndpoint("POST", "/api/v1/actions/restart?scope=http|full", "Restart HTTP engine or full gateway"),
            AdminEndpoint("GET", "/api/v1/assets/{type}?layer=", "Export bundled/runtime/merged asset overlays"),
            AdminEndpoint("POST", "/api/v1/assets/{type}", "Import runtime asset overlay (JSON map)"),
            AdminEndpoint("DELETE", "/api/v1/assets/{type}", "Clear runtime asset overlay"),
            AdminEndpoint("POST", "/api/v1/import/epg-csv", "Bulk EPG mapping import (CSV body)"),
            AdminEndpoint("GET", "/api/v1/categories/audit", "Find likely mis-categorized channels"),
            AdminEndpoint("POST", "/api/v1/categories/move", "Move channels to a category"),
            AdminEndpoint("POST", "/api/v1/overrides/category", "Set runtime category override"),
            AdminEndpoint("DELETE", "/api/v1/overrides/category", "Remove category override"),
        ),
    )

    override fun getSettings(): AdminSettingsSnapshot = snapshotSettings()

    override fun patchSettings(patch: AdminSettingsPatch): Pair<AdminSettingsSnapshot, Boolean> {
        var requiresRestart = false
        patch.port?.let {
            if (it in 1024..65535 && it != environment.port) {
                environment.port = it
                requiresRestart = true
            }
        }
        patch.networkAccessMode?.let { raw ->
            runCatching { NetworkAccessMode.valueOf(raw.trim().uppercase()) }
                .getOrNull()
                ?.let {
                    environment.networkAccessMode = it
                    requiresRestart = true
                    app.playlistCache.invalidate()
                }
        }
        patch.gatewayName?.let { environment.gatewayName = it }
        patch.dlhdBaseUrl?.let { environment.dlhdBaseUrl = it }
        patch.mirrorUrls?.let { environment.mirrorUrls = it }
        patch.supplementSportsEnabled?.let { environment.supplementSportsEnabled = it }
        patch.supplementIptvOrgEnabled?.let { environment.supplementIptvOrgEnabled = it }
        patch.supplementNtvCxEnabled?.let { environment.supplementNtvCxEnabled = it }
        patch.supplementAdultSwimEnabled?.let { environment.supplementAdultSwimEnabled = it }
        patch.supplementFreeTvEnabled?.let { environment.supplementFreeTvEnabled = it }
        patch.supplementDuloCxEnabled?.let { environment.supplementDuloCxEnabled = it }
        patch.supplementDuloCxAccessToken?.let { environment.supplementDuloCxAccessToken = it }
        patch.supplementTmdbMoviesEnabled?.let { environment.supplementTmdbMoviesEnabled = it }
        patch.gatewayEpgEnabled?.let { environment.gatewayEpgEnabled = it }
        patch.externalEpgUrl?.let { environment.externalEpgUrl = it }
        patch.iptvOrgEpgEnabled?.let { environment.iptvOrgEpgEnabled = it }
        patch.iptvOrgEpgUrl?.let { environment.iptvOrgEpgUrl = it }
        patch.startOnBoot?.let { environment.startOnBoot = it }
        patch.autoStartOnLaunch?.let { environment.autoStartOnLaunch = it }
        patch.autoLaunchTiviMate?.let { environment.autoLaunchTiviMate = it }
        if (patch.gatewayEpgEnabled != null || patch.externalEpgUrl != null ||
            patch.iptvOrgEpgEnabled != null || patch.iptvOrgEpgUrl != null
        ) {
            app.playlistCache.invalidate()
        }
        return snapshotSettings() to requiresRestart
    }

    override suspend fun refreshChannels(force: Boolean): AdminActionResult {
        val done = CompletableDeferred<Unit>()
        client.scheduleChannelRefresh(force = force) { done.complete(Unit) }
        val finished = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { done.await() } != null
        prewarmPlaylist()
        epgManager.scheduleRefresh(client.channels, force = true)
        return AdminActionResult(
            ok = finished,
            action = "refresh-channels",
            message = if (finished) "Channel list refreshed" else "Channel refresh timed out (may still be running)",
            channels = client.channels.size,
        )
    }

    override suspend fun refreshSupplements(force: Boolean): AdminActionResult {
        app.supplementSource.refresh(
            client.channels,
            force = force,
            dlhdScheduleBaseUrl = client.activeBaseUrl,
        )
        prewarmPlaylist()
        epgManager.scheduleRefresh(client.channels, force = true)
        return AdminActionResult(
            ok = true,
            action = "refresh-supplements",
            message = "Supplement sync complete",
            channels = client.channels.size,
            supplements = app.supplementSource.channelCount(),
        )
    }

    override suspend fun refreshEpg(force: Boolean): AdminActionResult {
        if (!environment.gatewayEpgEnabled) {
            return AdminActionResult(
                ok = false,
                action = "refresh-epg",
                message = "Gateway EPG is disabled — TiviMate uses external EPG from the playlist",
            )
        }
        epgManager.scheduleRefresh(client.channels, force = force)
        return AdminActionResult(
            ok = true,
            action = "refresh-epg",
            message = "EPG rebuild scheduled",
            channels = client.channels.size,
        )
    }

    override suspend fun refreshLogos(): AdminActionResult {
        runCatching { logoResolver.awaitLoaded(120_000L) }
        val channelResult = client.reEnrichLogos()
        val supplementResult = app.supplementSource.reEnrichLogos()
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "refresh-logos",
            message = "Logo enrich complete",
            assigned = channelResult.assigned + supplementResult.assigned,
            scanned = channelResult.scanned + supplementResult.scanned,
        )
    }

    override suspend fun refreshTvgIds(): AdminActionResult {
        app.tvgIdResolver.backfillUnmapped(
            context,
            app.epgChannelMapper,
            client.channels,
        )
        epgManager.scheduleRefresh(client.channels, force = true)
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "refresh-tvg-ids",
            message = "tvg-id backfill complete",
            channels = client.channels.size,
        )
    }

    override fun prewarmPlaylist(): AdminActionResult {
        prewarmPlaylist.invoke()
        return AdminActionResult(
            ok = true,
            action = "prewarm-playlist",
            message = "Playlist cache rebuild scheduled",
        )
    }

    override fun searchChannels(query: String, limit: Int): AdminChannelSearchResult {
        val needle = query.trim().lowercase()
        val cap = limit.coerceIn(1, 200)
        val matches = buildList {
            if (needle.isEmpty()) return@buildList
            client.channels.forEach { channel ->
                if (channel.name.lowercase().contains(needle)) {
                    add(channelSummary(channel.id, channel.name, channel.tags, channel.tvgId, "daddylive"))
                }
            }
            app.supplementSource.channels().forEach { supplement ->
                if (supplement.name.lowercase().contains(needle)) {
                    add(
                        channelSummary(
                            supplement.id,
                            supplement.name,
                            supplement.tags,
                            supplement.tvgId,
                            "supplement",
                        ),
                    )
                }
            }
        }.take(cap)
        return AdminChannelSearchResult(
            query = query,
            count = matches.size,
            channels = matches,
        )
    }

    override fun setLogoOverride(channelName: String, url: String): AdminActionResult {
        val name = channelName.trim()
        val remote = url.trim()
        if (name.isEmpty() || !remote.startsWith("http")) {
            return AdminActionResult(
                ok = false,
                action = "set-logo-override",
                message = "channelName and http(s) url required",
            )
        }
        logoResolver.putRuntimeOverride(name, remote)
        logoResolver.saveRuntimeOverrides(context)
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "set-logo-override",
            message = "Logo override saved for $name",
        )
    }

    override fun clearLogoOverride(channelName: String): AdminActionResult {
        val removed = logoResolver.removeRuntimeOverride(channelName.trim())
        if (removed) {
            logoResolver.saveRuntimeOverrides(context)
            prewarmPlaylist()
        }
        return AdminActionResult(
            ok = removed,
            action = "clear-logo-override",
            message = if (removed) "Logo override removed" else "No runtime override found",
        )
    }

    override fun setEpgNameOverride(channelName: String, tvgId: String): AdminActionResult {
        val name = channelName.trim()
        val id = tvgId.trim()
        if (name.isEmpty() || id.isEmpty()) {
            return AdminActionResult(
                ok = false,
                action = "set-epg-name-override",
                message = "channelName and tvgId required",
            )
        }
        app.epgChannelMapper.putRuntimeNameOverride(name, id)
        app.epgChannelMapper.saveRuntimeNameOverrides(context)
        epgManager.scheduleRefresh(client.channels, force = true)
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "set-epg-name-override",
            message = "EPG name override saved",
        )
    }

    override fun setEpgIdOverride(channelId: String, tvgId: String): AdminActionResult {
        val id = channelId.trim()
        val tvg = tvgId.trim()
        if (id.isEmpty() || tvg.isEmpty()) {
            return AdminActionResult(
                ok = false,
                action = "set-epg-id-override",
                message = "channelId and tvgId required",
            )
        }
        app.epgChannelMapper.putRuntimeIdOverride(id, tvg)
        app.epgChannelMapper.saveRuntimeIdMap(context)
        epgManager.scheduleRefresh(client.channels, force = true)
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "set-epg-id-override",
            message = "EPG id override saved",
        )
    }

    override fun resolveLogo(channelName: String, tvgId: String?): ResolveLogoResult {
        val remote = logoResolver.findBackfillLogo(channelName, tvgId, metaLogo = null)
        return ResolveLogoResult(
            channelName = channelName,
            tvgId = tvgId,
            logoUrl = remote,
            resolved = !remote.isNullOrBlank(),
        )
    }

    override fun resolveEpg(channelName: String): ResolveEpgResult {
        val fromMapper = app.epgChannelMapper.tvgIdForName(channelName)
        val tvgId = fromMapper ?: app.tvgIdResolver.resolve(channelName)?.tvgId
        return ResolveEpgResult(
            channelName = channelName,
            tvgId = tvgId,
            resolved = !tvgId.isNullOrBlank(),
        )
    }

    override fun stopGateway(): AdminActionResult {
        stopGatewayAction.invoke()
        return AdminActionResult(ok = true, action = "stop", message = "Gateway stop requested")
    }

    override fun restartGateway(scope: String): AdminActionResult = when (scope.lowercase()) {
        "full" -> {
            restartFullAction.invoke()
            AdminActionResult(ok = true, action = "restart-full", message = "Full gateway restart scheduled")
        }
        else -> {
            restartHttpAction.invoke()
            AdminActionResult(ok = true, action = "restart-http", message = "HTTP engine restart scheduled")
        }
    }

    override fun getChannel(channelId: String): AdminChannelSummary? {
        val id = channelId.trim()
        client.channels.firstOrNull { it.id == id }?.let { channel ->
            return channelSummary(channel.id, channel.name, channel.tags, channel.tvgId, "daddylive")
        }
        app.supplementSource.channels().firstOrNull { it.id == id }?.let { supplement ->
            return channelSummary(supplement.id, supplement.name, supplement.tags, supplement.tvgId, "supplement")
        }
        return null
    }

    override fun setCategoryOverride(
        channelId: String?,
        channelName: String?,
        groupTitle: String,
    ): AdminActionResult = runCatching {
        CategoryOverrideStore.put(context, channelId, channelName, groupTitle)
        prewarmPlaylist()
        AdminActionResult(ok = true, action = "set-category-override", message = "Category override saved")
    }.getOrElse { exc ->
        AdminActionResult(ok = false, action = "set-category-override", message = exc.message ?: "failed")
    }

    override fun clearCategoryOverride(channelId: String?, channelName: String?): AdminActionResult {
        val removed = CategoryOverrideStore.remove(context, channelId, channelName)
        if (removed) prewarmPlaylist()
        return AdminActionResult(
            ok = removed,
            action = "clear-category-override",
            message = if (removed) "Category override removed" else "No override found",
        )
    }

    override fun moveCategories(channelIds: List<String>, groupTitle: String): AdminActionResult =
        runCatching {
            require(groupTitle in CategoryOverrideStore.validGroups) { "Invalid groupTitle: $groupTitle" }
            val entries = channelIds.mapNotNull { id ->
                val trimmed = id.trim()
                if (trimmed.isEmpty()) null else Triple(trimmed, null as String?, groupTitle)
            }
            CategoryOverrideStore.putBatch(context, entries)
            prewarmPlaylist()
            AdminActionResult(
                ok = true,
                action = "move-categories",
                message = "Moved ${entries.size} channels to $groupTitle",
            )
        }.getOrElse { exc ->
            AdminActionResult(ok = false, action = "move-categories", message = exc.message ?: "failed")
        }

    override fun categoryAudit(limit: Int, groupFilter: String?): CategoryAuditResult {
        val cap = limit.coerceIn(1, 1000)
        val filter = groupFilter?.trim()?.takeIf { it.isNotEmpty() }
        val entries = mutableListOf<CategoryAuditEntry>()
        fun consider(id: String, name: String, tags: List<String>, source: String) {
            val current = GroupTitleResolver.resolve(name, tags, id).groupTitle
            if (filter != null && current != filter) return
            val suggested = suggestGroup(name, current) ?: return
            entries += CategoryAuditEntry(
                id = id,
                name = name,
                source = source,
                currentGroup = current,
                suggestedGroup = suggested,
                reason = "Matcher suggests $suggested",
            )
        }
        client.channels.forEach { consider(it.id, it.name, it.tags, "daddylive") }
        app.supplementSource.channels().forEach { consider(it.id, it.name, it.tags, "supplement") }
        val sorted = entries.sortedWith(compareBy({ it.currentGroup }, { it.name })).take(cap)
        return CategoryAuditResult(
            scanned = client.channels.size + app.supplementSource.channelCount(),
            misplacements = sorted.size,
            entries = sorted,
        )
    }

    override fun exportAssets(type: String, layer: String): AssetExportResult {
        val assetType = parseAssetType(type)
            ?: return AssetExportResult(type = type, layer = layer, count = 0)
        val entries = assetManager.export(assetType, layer)
        return AssetExportResult(type = type, layer = layer, count = entries.size, entries = entries)
    }

    override fun importAssets(type: String, entries: Map<String, String>, merge: Boolean): AdminImportResult {
        val assetType = parseAssetType(type)
            ?: return AdminImportResult(ok = false, message = "Unknown asset type: $type")
        val result = assetManager.importJson(assetType, entries, merge, logoResolver)
        prewarmPlaylist()
        if (assetType == AdminAssetManager.AssetType.EPG_NAME ||
            assetType == AdminAssetManager.AssetType.EPG_ID ||
            assetType == AdminAssetManager.AssetType.EPG_RESEARCH
        ) {
            epgManager.scheduleRefresh(client.channels, force = true)
        }
        return result
    }

    override fun importEpgCsv(csv: String): AdminImportResult {
        val result = assetManager.importEpgCsv(csv, app.epgChannelMapper)
        if (result.imported > 0) {
            epgManager.scheduleRefresh(client.channels, force = true)
            prewarmPlaylist()
        }
        return result
    }

    override fun clearRuntimeAssets(type: String): AdminActionResult {
        val assetType = parseAssetType(type)
            ?: return AdminActionResult(ok = false, action = "clear-assets", message = "Unknown type: $type")
        assetManager.clearRuntime(assetType)
        prewarmPlaylist()
        return AdminActionResult(ok = true, action = "clear-assets", message = "Runtime ${assetType.label} cleared")
    }

    override suspend fun resolveStream(channelId: String, probe: Boolean): ResolveStreamResult {
        val id = channelId.trim()
        val base = environment.loopbackBase()
        client.channels.firstOrNull { it.id == id }?.let { channel ->
            val playUrl = AdminStreamHelper.daddylivePlayUrl(
                base,
                channel.id,
                AdminStreamHelper.dlhdOrigin(environment.dlhdBaseUrl),
            )
            val probeResult = if (probe) probeDaddyliveStream(channel.id) else null
            return ResolveStreamResult(
                channelId = id,
                channelName = channel.name,
                source = "daddylive",
                playUrl = playUrl,
                resolved = true,
                probe = probeResult,
            )
        }
        app.supplementSource.channels().firstOrNull { it.id == id }?.let { supplement ->
            val playUrl = AdminStreamHelper.supplementPlayUrl(base, supplement)
            return ResolveStreamResult(
                channelId = id,
                channelName = supplement.name,
                source = supplementSourceLabel(supplement),
                playUrl = playUrl,
                resolved = playUrl.isNotBlank(),
                probe = null,
            )
        }
        return ResolveStreamResult(
            channelId = id,
            source = "unknown",
            playUrl = "",
            resolved = false,
        )
    }

    private suspend fun probeDaddyliveStream(channelId: String): StreamProbeResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val playlist = withTimeoutOrNull(STREAM_PROBE_TIMEOUT_MS) {
                    client.resolveStream(
                        channelId,
                        useProxy = true,
                        apiUrl = environment.loopbackBase(),
                    )
                } ?: return@runCatching StreamProbeResult(
                    ok = false,
                    error = "timeout",
                )
                StreamProbeResult(
                    ok = playlist.contains("#EXTM3U"),
                    cached = client.wasLastServeFromStaleCache(),
                    bytes = playlist.length,
                )
            }.getOrElse { exc ->
                StreamProbeResult(ok = false, error = exc.message ?: "probe_failed")
            }
        }

    private fun suggestGroup(name: String, current: String): String? {
        if (PremiumMovieChannelMatcher.matches(name) && current != GroupTitleResolver.MOVIES) {
            return GroupTitleResolver.MOVIES
        }
        return null
    }

    private fun supplementSourceLabel(supplement: SupplementChannel): String = when {
        supplement.id.startsWith("iptv:") -> "iptv-org"
        supplement.id.startsWith(FreeTvIptvConfig.ID_PREFIX) -> "free-tv"
        supplement.id.startsWith(DuloCxLiveConfig.ID_PREFIX) -> "dulo.cx"
        supplement.id.startsWith("ntv:") -> "ntv.cx"
        supplement.id.startsWith("dlhd-guide:") ||
            supplement.id.startsWith("dlhd-event:") -> "special-events"
        else -> "supplement"
    }

    private fun parseAssetType(raw: String): AdminAssetManager.AssetType? = when (raw.lowercase()) {
        "epg-name-overrides", "epg-name", "epg_name" -> AdminAssetManager.AssetType.EPG_NAME
        "logo-overrides", "logo", "logos" -> AdminAssetManager.AssetType.LOGO
        "epg-id-map", "epg-id", "epg_id" -> AdminAssetManager.AssetType.EPG_ID
        "epg-research", "epg_research", "daddylive-epg-research" -> AdminAssetManager.AssetType.EPG_RESEARCH
        "category-overrides", "category", "categories" -> AdminAssetManager.AssetType.CATEGORY
        else -> null
    }

    private fun channelSummary(
        id: String,
        name: String,
        tags: List<String>,
        tvgId: String?,
        source: String,
    ): AdminChannelSummary {
        val resolution = GroupTitleResolver.resolve(name, tags, id)
        return AdminChannelSummary(
            id = id,
            name = name,
            groupTitle = resolution.groupTitle,
            countryCode = resolution.countryCode,
            tvgId = tvgId,
            source = source,
        )
    }

    private fun snapshotSettings(): AdminSettingsSnapshot = AdminSettingsSnapshot(
        port = environment.port,
        networkAccessMode = environment.networkAccessMode.name,
        gatewayName = environment.displayGatewayName(),
        dlhdBaseUrl = environment.dlhdBaseUrl,
        mirrorUrls = environment.mirrorUrls,
        supplementSportsEnabled = environment.supplementSportsEnabled,
        supplementIptvOrgEnabled = environment.supplementIptvOrgEnabled,
        supplementNtvCxEnabled = environment.supplementNtvCxEnabled,
        supplementAdultSwimEnabled = environment.supplementAdultSwimEnabled,
        supplementFreeTvEnabled = environment.supplementFreeTvEnabled,
        supplementDuloCxEnabled = environment.supplementDuloCxEnabled,
        supplementTmdbMoviesEnabled = environment.supplementTmdbMoviesEnabled,
        gatewayEpgEnabled = environment.gatewayEpgEnabled,
        externalEpgUrl = environment.externalEpgUrlForDisplay(),
        iptvOrgEpgEnabled = environment.iptvOrgEpgEnabled,
        iptvOrgEpgUrl = environment.iptvOrgEpgUrl,
        startOnBoot = environment.startOnBoot,
        autoStartOnLaunch = environment.autoStartOnLaunch,
        autoLaunchTiviMate = environment.autoLaunchTiviMate,
    )

    companion object {
        private const val REFRESH_TIMEOUT_MS = 120_000L
        private const val STREAM_PROBE_TIMEOUT_MS = 25_000L
    }
}
