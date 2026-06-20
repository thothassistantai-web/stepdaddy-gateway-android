package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig.TIVIMATE_USER_AGENT

object PlaylistBuilder {
    fun minimalPlaylist(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return "#EXTM3U url-tvg=\"$base/epg.xml\" x-tvg-url=\"$base/epg.xml\"\n"
    }

    fun tivimatePlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
    ): String = GroupTitleResolver.withResolveCache {
        buildTivimatePlaylist(
            channels = channels,
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
        )
    }

    private fun buildTivimatePlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
    ): String {
        val base = baseUrl.trimEnd('/')
        val channelNumbers = ChannelNumberResolver.assignAll(channels)
        val supplementNumbers = ChannelNumberResolver.assignSupplements(
            channels = channels,
            supplements = supplements,
            groupFor = ChannelNumberResolver::supplementGroup,
            channelNumbers = channelNumbers,
        )
        val rows = ArrayList<PlaylistRow>(channels.size + supplements.size)

        channels.forEach { channel ->
            val chno = channelNumbers[channel.id] ?: return@forEach
            val resolution = GroupTitleResolver.resolve(channel.name, channel.tags)
            val title = ChannelTitleNormalizer.displayTitle(channel.name, resolution)
            rows += PlaylistRow(
                chno = chno,
                extinf = "#EXTINF:-1 ${extinfAttrs(channel, base, logoResolver, channelMetaStore, resolution, chno)},$title",
                stream = tivimateStreamLine(base, channel.id, dlhdOrigin),
            )
        }

        supplements.forEach { supplement ->
            val chno = supplementNumbers[supplement.id] ?: return@forEach
            rows += PlaylistRow(
                chno = chno,
                extinf = "#EXTINF:-1 ${supplementExtinfAttrs(supplement, base, logoResolver, chno)},${supplementDisplayTitle(supplement)}",
                stream = supplementStreamLine(supplement, base),
            )
        }

        rows.sortBy { it.chno }

        val estimatedBytes = rows.size * 420 + 128
        val out = StringBuilder(estimatedBytes.coerceAtMost(8 * 1024 * 1024))
        out.append("#EXTM3U url-tvg=\"$base/epg.xml\" x-tvg-url=\"$base/epg.xml\"\n")
        rows.forEach { row ->
            out.append(row.extinf).append('\n')
            out.append(row.stream).append('\n')
        }
        return out.toString()
    }

    private data class PlaylistRow(
        val chno: Int,
        val extinf: String,
        val stream: String,
    )

    private fun extinfAttrs(
        channel: Channel,
        base: String,
        logoResolver: LogoResolver?,
        channelMetaStore: ChannelMetaStore?,
        resolution: GroupTitleResolver.Resolution,
        channelNumber: Int,
    ): String {
        val attrs = mutableListOf<String>()
        channel.tvgId?.let {
            attrs += """tvg-id="${escape(it)}""""
            attrs += """tvg-name="${escape(channel.name)}""""
        }
        val metaLogo = channelMetaStore?.logoFor(channel.name)
        val logo = logoResolver?.resolvePlaylistLogoUrl(
            apiBase = base,
            channelName = channel.name,
            tvgId = channel.tvgId,
            metaLogo = metaLogo,
            channelLogo = channel.logo,
        ) ?: channel.logo?.takeIf { it.startsWith("http") }
            ?: "$base/ui/default-channel.svg"
        attrs += """tvg-logo="${escape(logo)}""""
        attrs += """group-title="${escape(resolution.groupTitle)}""""
        attrs += """tvg-chno="$channelNumber""""
        return attrs.joinToString(" ")
    }

    private fun supplementDisplayTitle(supplement: SupplementChannel): String {
        val resolution = supplementResolution(supplement)
        return         if (supplement.id.startsWith("iptv:") || supplement.id.startsWith("ntv:")) {
            ChannelTitleNormalizer.supplementDisplayTitle(
                supplement.name,
                resolution,
                supplement.providerTag,
            )
        } else {
            escape(supplement.name)
        }
    }

    private fun supplementResolution(supplement: SupplementChannel): GroupTitleResolver.Resolution {
        if (supplement.id.startsWith("iptv:") && supplement.tags.isNotEmpty()) {
            return GroupTitleResolver.resolve(supplement.name, supplement.tags)
        }
        if (supplement.id.startsWith("iptv:")) {
            return GroupTitleResolver.Resolution(
                groupTitle = supplement.groupTitle,
                categoryLabel = supplement.groupTitle,
                countryCode = "",
                flagEmoji = null,
                isAdult = false,
                appendCountrySuffix = false,
            )
        }
        return GroupTitleResolver.Resolution(
            groupTitle = supplement.groupTitle,
            categoryLabel = supplement.groupTitle,
            countryCode = "",
            flagEmoji = null,
            isAdult = false,
            appendCountrySuffix = false,
        )
    }

    private fun tivimateStreamLine(base: String, channelId: String, dlhdOrigin: String): String {
        val stream = "${base.trimEnd('/')}/tivimate-stream/$channelId.m3u8"
        val origin = dlhdOrigin.trimEnd('/')
        return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$origin/|Origin=$origin"
    }

    private fun supplementStreamLine(supplement: SupplementChannel, base: String): String {
        if (supplement.id.startsWith("ntv:")) {
            val token = supplement.id.removePrefix("ntv:")
            val stream = "${base.trimEnd('/')}/ntv-stream/$token.m3u8"
            val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.REFERER
            val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.ORIGIN
            return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
        }
        val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() } ?: return supplement.streamUrl
        val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() } ?: referer.trimEnd('/')
        return "${supplement.streamUrl}|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
    }

    private fun supplementExtinfAttrs(
        supplement: SupplementChannel,
        base: String,
        logoResolver: LogoResolver?,
        channelNumber: Int,
    ): String {
        val resolution = supplementResolution(supplement)
        val groupTitle = if (supplement.id.startsWith("iptv:")) {
            resolution.groupTitle
        } else {
            supplement.groupTitle
        }
        val attrs = mutableListOf<String>()
        supplement.tvgId?.let {
            attrs += """tvg-id="${escape(it)}""""
            attrs += """tvg-name="${escape(supplement.name)}""""
        }
        val logo = logoResolver?.resolvePlaylistLogoUrl(
            apiBase = base,
            channelName = supplement.name,
            tvgId = supplement.tvgId,
            channelLogo = supplement.logo,
        ) ?: supplement.logo?.takeIf { it.startsWith("http") }
            ?: "$base/ui/default-channel.svg"
        attrs += """tvg-logo="${escape(logo)}""""
        attrs += """group-title="${escape(groupTitle)}""""
        attrs += """tvg-chno="$channelNumber""""
        return attrs.joinToString(" ")
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
}
