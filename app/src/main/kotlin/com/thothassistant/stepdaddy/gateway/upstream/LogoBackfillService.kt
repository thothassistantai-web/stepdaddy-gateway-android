package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.File
import org.json.JSONObject

/**
 * Finds logos for channels missing metadata/upstream logos, processing categories
 * from smallest to largest so niche groups get full DB attention first.
 */
class LogoBackfillService(
    private val context: Context,
    private val logoResolver: LogoResolver,
    private val channelMetaStore: ChannelMetaStore,
    private val apiBase: String,
) {
    data class Target(
        val name: String,
        val tvgId: String?,
        val metaLogo: String?,
        val channelLogo: String?,
        val groupTitle: String,
    )

    data class Result(
        val groupsProcessed: Int,
        val scanned: Int,
        val assigned: Int,
        val skipped: Int,
    )

    fun run(
        channels: List<Channel>,
        supplements: List<SupplementChannel>,
    ): Result {
        if (!logoResolver.isLoaded()) {
            return Result(0, 0, 0, 0)
        }

        val targets = buildList {
            channels.forEach { channel ->
                val resolution = GroupTitleResolver.resolve(channel.name, channel.tags)
                add(
                    Target(
                        name = channel.name,
                        tvgId = channel.tvgId,
                        metaLogo = channelMetaStore.logoFor(channel.name),
                        channelLogo = channel.logo,
                        groupTitle = resolution.groupTitle,
                    ),
                )
            }
            supplements.forEach { supplement ->
                add(
                    Target(
                        name = supplement.name,
                        tvgId = supplement.tvgId,
                        metaLogo = null,
                        channelLogo = supplement.logo,
                        groupTitle = ChannelNumberResolver.supplementGroup(supplement),
                    ),
                )
            }
        }

        val byGroup = targets.groupBy { it.groupTitle }
        val orderedGroups = sortedGroupEntries(byGroup)

        var assigned = 0
        var skipped = 0
        var scanned = 0

        for ((group, groupTargets) in orderedGroups) {
            var groupAssigned = 0
            for (target in groupTargets) {
                scanned++
                if (!needsBackfill(target)) {
                    skipped++
                    continue
                }
                val remote = logoResolver.findBackfillLogo(
                    channelName = target.name,
                    tvgId = target.tvgId,
                    metaLogo = target.metaLogo,
                ) ?: continue
                logoResolver.putRuntimeOverride(target.name, remote)
                assigned++
                groupAssigned++
            }
            if (groupAssigned > 0) {
                Log.i(TAG, "Logo backfill [$group] (${groupTargets.size} ch): +$groupAssigned")
            }
        }

        if (assigned > 0) {
            logoResolver.saveRuntimeOverrides(context)
            Log.i(
                TAG,
                "Logo backfill complete: assigned=$assigned skipped=$skipped scanned=$scanned " +
                    "groups=${orderedGroups.size}",
            )
        }

        return Result(
            groupsProcessed = orderedGroups.size,
            scanned = scanned,
            assigned = assigned,
            skipped = skipped,
        )
    }

    private fun needsBackfill(target: Target): Boolean {
        if (target.metaLogo?.trim()?.startsWith("http") == true) return false
        val logo = target.channelLogo?.trim().orEmpty()
        if (logo.startsWith("http://") || logo.startsWith("https://")) {
            if (!logoResolver.isGatewayPlaceholderUrl(apiBase, logo)) return false
        }
        if (logoResolver.hasResolvableLogo(target.name, target.tvgId, target.metaLogo)) return false
        return true
    }

    companion object {
        private const val TAG = "LogoBackfillService"
        const val RUNTIME_OVERRIDES_FILE = "logos/runtime_overrides.json"

        fun runtimeOverridesFile(context: Context): File =
            File(context.filesDir, RUNTIME_OVERRIDES_FILE)

        internal fun sortedGroupEntries(
            byGroup: Map<String, List<Target>>,
        ): List<Pair<String, List<Target>>> =
            byGroup.entries
                .sortedWith(compareBy({ it.value.size }, { it.key }))
                .map { it.key to it.value }
    }
}
