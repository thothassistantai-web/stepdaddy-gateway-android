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
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
    ): String = GroupTitleResolver.withResolveCache {
        buildTivimatePlaylist(
            channels = channels,
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = titleStyle,
        )
    }

    private fun buildTivimatePlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
    ): String {
        val base = baseUrl.trimEnd('/')
        val (channelNumbers, supplementNumbers) = ChannelNumberResolver.assignPlaylist(channels, supplements)
        val rows = ArrayList<PlaylistRow>(channels.size + supplements.size)

        channels.forEach { channel ->
            val chno = channelNumbers[channel.id] ?: return@forEach
            val resolution = GroupTitleResolver.resolve(channel.name, channel.tags, channel.id)
            val title = ChannelTitleNormalizer.displayTitle(
                channelName = channel.name,
                resolution = resolution,
                style = titleStyle,
                source = if (resolution.isAdult) PlaylistTitleSource.ADULT else PlaylistTitleSource.CABLE,
            )
            rows += PlaylistRow(
                groupOrder = GroupTitleResolver.groupSortOrder(resolution.groupTitle),
                chno = chno,
                extinf = "#EXTINF:-1 ${extinfAttrs(channel, base, logoResolver, channelMetaStore, resolution, chno, title, titleStyle)},$title",
                stream = tivimateStreamLine(base, channel.id, dlhdOrigin),
            )
        }

        supplements.forEach { supplement ->
            val chno = supplementNumbers[supplement.id] ?: return@forEach
            val title = supplementDisplayTitle(supplement, titleStyle)
            val groupTitle = supplementPlaylistGroupTitle(supplement)
            rows += PlaylistRow(
                groupOrder = GroupTitleResolver.groupSortOrder(groupTitle),
                chno = chno,
                extinf = "#EXTINF:-1 ${supplementExtinfAttrs(supplement, base, logoResolver, chno, title, titleStyle)},$title",
                stream = supplementStreamLine(supplement, base),
            )
        }

        rows.sortWith(compareBy({ it.groupOrder }, { it.chno }))

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
        val groupOrder: Int,
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
        displayTitle: String,
        titleStyle: PlaylistTitleStyle,
    ): String {
        val attrs = mutableListOf<String>()
        channel.tvgId?.let {
            attrs += """tvg-id="${escape(it)}""""
            val tvgName = if (titleStyle == PlaylistTitleStyle.XTREAM_CATEGORY) displayTitle else channel.name
            attrs += """tvg-name="${escape(tvgName)}""""
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

    private fun supplementDisplayTitle(
        supplement: SupplementChannel,
        titleStyle: PlaylistTitleStyle,
    ): String {
        val resolution = supplementResolution(supplement)
        val source = supplementTitleSource(supplement, resolution)
        return if (supplement.id.startsWith("iptv:") || supplement.id.startsWith("ntv:") ||
            supplement.id.startsWith("adultswim:") || titleStyle == PlaylistTitleStyle.XTREAM_CATEGORY
        ) {
            ChannelTitleNormalizer.supplementDisplayTitle(
                channelName = supplement.name,
                resolution = resolution,
                providerTag = supplement.providerTag,
                style = titleStyle,
                source = source,
            )
        } else {
            escape(supplement.name)
        }
    }

    private fun supplementTitleSource(
        supplement: SupplementChannel,
        resolution: GroupTitleResolver.Resolution,
    ): PlaylistTitleSource = when {
        resolution.isAdult -> PlaylistTitleSource.ADULT
        supplement.id.startsWith("iptv:") ||
            supplement.id.startsWith("ntv:") ||
            supplement.id.startsWith("adultswim:") -> PlaylistTitleSource.FAST
        supplement.id.startsWith("sport:") -> PlaylistTitleSource.SIDECAR
        else -> PlaylistTitleSource.SIDECAR
    }

    private fun supplementResolution(supplement: SupplementChannel): GroupTitleResolver.Resolution {
        if (supplement.id.startsWith("iptv:")) {
            return GroupTitleResolver.resolve(supplement.name, supplement.tags, supplement.id)
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
        displayTitle: String,
        titleStyle: PlaylistTitleStyle,
    ): String {
        val resolution = supplementResolution(supplement)
        val groupTitle = supplementPlaylistGroupTitle(supplement, resolution)
        val attrs = mutableListOf<String>()
        supplement.tvgId?.let {
            attrs += """tvg-id="${escape(it)}""""
            val tvgName = if (titleStyle == PlaylistTitleStyle.XTREAM_CATEGORY) displayTitle else supplement.name
            attrs += """tvg-name="${escape(tvgName)}""""
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

    private fun supplementPlaylistGroupTitle(
        supplement: SupplementChannel,
        resolution: GroupTitleResolver.Resolution = supplementResolution(supplement),
    ): String =
        if (supplement.id.startsWith("iptv:")) {
            resolution.groupTitle
        } else {
            supplement.groupTitle
        }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
}
