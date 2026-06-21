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
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.upstream.LogoBackfillService
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class GatewayAdminController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val epgManager: EpgManager,
    private val app: GatewayApp,
    private val logoResolver: LogoResolver,
    private val prewarmPlaylist: () -> Unit,
    private val runLogoBackfill: suspend () -> LogoBackfillService.Result,
) : GatewayAdminActions {

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
                }
        }
        patch.gatewayName?.let { environment.gatewayName = it }
        patch.dlhdBaseUrl?.let { environment.dlhdBaseUrl = it }
        patch.mirrorUrls?.let { environment.mirrorUrls = it }
        patch.embeddedSidecarEnabled?.let { environment.embeddedSidecarEnabled = it }
        patch.supplementBaseUrl?.let { environment.supplementBaseUrl = it }
        patch.supplementSportsEnabled?.let { environment.supplementSportsEnabled = it }
        patch.supplementIptvOrgEnabled?.let { environment.supplementIptvOrgEnabled = it }
        patch.supplementNtvCxEnabled?.let { environment.supplementNtvCxEnabled = it }
        patch.supplementAdultSwimEnabled?.let { environment.supplementAdultSwimEnabled = it }
        patch.iptvOrgEpgEnabled?.let { environment.iptvOrgEpgEnabled = it }
        patch.iptvOrgEpgUrl?.let { environment.iptvOrgEpgUrl = it }
        patch.startOnBoot?.let { environment.startOnBoot = it }
        patch.autoStartOnLaunch?.let { environment.autoStartOnLaunch = it }
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
        app.supplementSource.refresh(client.channels, force = force)
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
        epgManager.scheduleRefresh(client.channels, force = force)
        return AdminActionResult(
            ok = true,
            action = "refresh-epg",
            message = "EPG rebuild scheduled",
            channels = client.channels.size,
        )
    }

    override suspend fun refreshLogos(): AdminActionResult {
        logoResolver.awaitLoaded()
        val result = runLogoBackfill()
        prewarmPlaylist()
        return AdminActionResult(
            ok = true,
            action = "refresh-logos",
            message = "Logo backfill complete",
            assigned = result.assigned,
            scanned = result.scanned,
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

    private fun channelSummary(
        id: String,
        name: String,
        tags: List<String>,
        tvgId: String?,
        source: String,
    ): AdminChannelSummary {
        val resolution = GroupTitleResolver.resolve(name, tags)
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
        embeddedSidecarEnabled = environment.embeddedSidecarEnabled,
        supplementBaseUrl = environment.supplementBaseUrl,
        supplementSportsEnabled = environment.supplementSportsEnabled,
        supplementIptvOrgEnabled = environment.supplementIptvOrgEnabled,
        supplementNtvCxEnabled = environment.supplementNtvCxEnabled,
        supplementAdultSwimEnabled = environment.supplementAdultSwimEnabled,
        iptvOrgEpgEnabled = environment.iptvOrgEpgEnabled,
        iptvOrgEpgUrl = environment.iptvOrgEpgUrl,
        startOnBoot = environment.startOnBoot,
        autoStartOnLaunch = environment.autoStartOnLaunch,
    )

    companion object {
        private const val REFRESH_TIMEOUT_MS = 120_000L
    }
}
