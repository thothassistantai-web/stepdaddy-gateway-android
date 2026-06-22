package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/**
 * Resolves remote logo URLs when the channel catalog is ingested or refreshed.
 * Playlist build reads [Channel.logo] / [SupplementChannel.logo] — no separate backfill pass.
 */
class CatalogLogoEnricher(
    private val logoResolver: LogoResolver,
    private val channelMetaStore: ChannelMetaStore?,
) {
    data class Result(
        val scanned: Int,
        val assigned: Int,
        val skipped: Int,
    )

    fun enrichChannels(channels: List<Channel>): Pair<List<Channel>, Result> {
        var assigned = 0
        var skipped = 0
        val enriched = channels.map { channel ->
            if (!needsEnrich(channel.logo)) {
                skipped++
                return@map channel
            }
            val metaLogo = channelMetaStore?.logoFor(channel.name)
            val remote = logoResolver.resolveIngestRemoteLogo(
                channelName = channel.name,
                tvgId = channel.tvgId,
                metaLogo = metaLogo,
                existingLogo = channel.logo,
            )
            if (remote != null) {
                assigned++
                channel.copy(logo = remote)
            } else {
                skipped++
                channel
            }
        }
        if (assigned > 0) {
            Log.i(TAG, "Channel logo enrich: +$assigned / ${channels.size}")
        }
        return enriched to Result(channels.size, assigned, skipped)
    }

    fun enrichSupplements(supplements: List<SupplementChannel>): Pair<List<SupplementChannel>, Result> {
        var assigned = 0
        var skipped = 0
        val enriched = supplements.map { supplement ->
            if (!needsEnrich(supplement.logo)) {
                skipped++
                return@map supplement
            }
            val remote = logoResolver.resolveIngestRemoteLogo(
                channelName = supplement.name,
                tvgId = supplement.tvgId,
                metaLogo = null,
                existingLogo = supplement.logo,
            )
            if (remote != null) {
                assigned++
                supplement.copy(logo = remote)
            } else {
                skipped++
                supplement
            }
        }
        if (assigned > 0) {
            Log.i(TAG, "Supplement logo enrich: +$assigned / ${supplements.size}")
        }
        return enriched to Result(supplements.size, assigned, skipped)
    }

    private fun needsEnrich(logo: String?): Boolean {
        if (logo.isNullOrBlank()) return true
        val trimmed = logo.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return true
        if (trimmed.contains("/ui/channel/") || trimmed.contains("/ui/default-channel")) return true
        return false
    }

    companion object {
        private const val TAG = "CatalogLogoEnricher"
    }
}
