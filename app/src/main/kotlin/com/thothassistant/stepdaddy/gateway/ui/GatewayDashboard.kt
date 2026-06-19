package com.thothassistant.stepdaddy.gateway.ui

import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.GroupTitleResolver
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource

data class ProviderChannelCounts(
    val daddylive: Int = 0,
    val moveOnJoy: Int = 0,
    val iptvOrg: Int = 0,
    val sports: Int = 0,
    val adult: Int = 0,
    val total: Int = 0,
)

data class CategoryChannelCount(
    val groupTitle: String,
    val count: Int,
)

object GatewayDashboard {
    fun providerCounts(
        client: DaddyLiveClient?,
        supplementSource: SupplementSource?,
    ): ProviderChannelCounts {
        val dl = client?.channels.orEmpty()
        val adult = dl.count { isAdultChannel(it) }
        val supp = supplementSource
        return ProviderChannelCounts(
            daddylive = dl.size,
            moveOnJoy = supp?.moveOnJoyCount() ?: 0,
            iptvOrg = supp?.iptvOrgCount() ?: 0,
            sports = supp?.sportsCount() ?: 0,
            adult = adult,
            total = dl.size + (supp?.channelCount() ?: 0),
        )
    }

    fun categoryCounts(channels: List<Channel>): List<CategoryChannelCount> {
        val counts = linkedMapOf<String, Int>()
        channels.forEach { channel ->
            val group = GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle
            counts[group] = (counts[group] ?: 0) + 1
        }
        return counts.entries
            .map { CategoryChannelCount(it.key, it.value) }
            .sortedByDescending { it.count }
    }

    fun fromApp(app: GatewayApp, client: DaddyLiveClient?): Pair<ProviderChannelCounts, List<CategoryChannelCount>> {
        val providers = providerCounts(client, app.supplementSource)
        val categories = categoryCounts(client?.channels.orEmpty())
        return providers to categories
    }

    private fun isAdultChannel(channel: Channel): Boolean =
        GroupTitleResolver.resolve(channel.name, channel.tags).groupTitle == GroupTitleResolver.ADULT
}
