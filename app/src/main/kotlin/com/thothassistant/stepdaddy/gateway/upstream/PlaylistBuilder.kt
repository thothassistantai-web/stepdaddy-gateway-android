package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.epg.PlaylistEpgHeader
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig.TIVIMATE_USER_AGENT
import java.time.Instant

object PlaylistBuilder {
    private enum class StreamUrlStyle {
        TIVIMATE_PIPE,
        PLAIN,
    }

    fun minimalPlaylist(baseUrl: String, epgUrl: String? = null): String =
        PlaylistEpgHeader.line(epgUrl)

    fun streamVaultPlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        epgUrl: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String = GroupTitleResolver.withResolveCache {
        buildTivimatePlaylist(
            channels = channels,
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = titleStyle,
            epgUrl = epgUrl,
            streamUrlStyle = StreamUrlStyle.PLAIN,
            nowMs = nowMs,
            eventHealthStore = eventHealthStore,
        )
    }

    /** Full catalog with plain gateway proxy URLs (no TiviMate pipe suffixes). */
    fun streamVaultSetupPlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        epgUrl: String? = null,
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String = streamVaultPlaylist(
        channels = channels,
        baseUrl = baseUrl,
        dlhdOrigin = dlhdOrigin,
        logoResolver = logoResolver,
        channelMetaStore = channelMetaStore,
        supplements = supplements,
        titleStyle = titleStyle,
        epgUrl = epgUrl,
        eventHealthStore = eventHealthStore,
    )

    fun tivimatePlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        epgUrl: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String = GroupTitleResolver.withResolveCache {
        buildTivimatePlaylist(
            channels = channels,
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = supplements,
            titleStyle = titleStyle,
            epgUrl = epgUrl,
            nowMs = nowMs,
            eventHealthStore = eventHealthStore,
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
        epgUrl: String? = null,
        streamUrlStyle: StreamUrlStyle = StreamUrlStyle.TIVIMATE_PIPE,
        nowMs: Long = System.currentTimeMillis(),
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String {
        val now = Instant.ofEpochMilli(nowMs)
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
                stream = channelStreamLine(base, channel.id, dlhdOrigin, streamUrlStyle),
            )
        }

        supplements.forEach { supplement ->
            if (!SpecialEventLifecycle.isDlhdEventPlaylistVisible(supplement, now)) return@forEach
            val chno = supplementNumbers[supplement.id] ?: return@forEach
            val title = supplementDisplayTitle(supplement, titleStyle, nowMs, eventHealthStore)
            val groupTitle = supplementPlaylistGroupTitle(supplement)
            rows += PlaylistRow(
                groupOrder = GroupTitleResolver.groupSortOrder(groupTitle),
                intraGroupSortKey = supplementIntraGroupSortKey(supplement),
                intraGroupOrder = supplementIntraGroupSlot(supplement),
                chno = chno,
                extinf = "#EXTINF:-1 ${supplementExtinfAttrs(supplement, base, logoResolver, chno, title, titleStyle)},$title",
                stream = supplementStreamLine(supplement, base, dlhdOrigin, streamUrlStyle),
            )
        }

        rows.sortWith(
            compareBy({ it.groupOrder }, { it.intraGroupSortKey }, { it.intraGroupOrder }, { it.chno }),
        )

        val estimatedBytes = rows.size * 420 + 128
        val out = StringBuilder(estimatedBytes.coerceAtMost(8 * 1024 * 1024))
        out.append(PlaylistEpgHeader.line(epgUrl))
        rows.forEach { row ->
            out.append(row.extinf).append('\n')
            out.append(row.stream).append('\n')
        }
        return out.toString()
    }

    fun tivimateSetupPlaylist(
        channels: List<Channel>,
        baseUrl: String,
        dlhdOrigin: String,
        logoResolver: LogoResolver? = null,
        channelMetaStore: ChannelMetaStore? = null,
        supplements: List<SupplementChannel> = emptyList(),
        titleStyle: PlaylistTitleStyle = PlaylistTitleStyle.XTREAM_CATEGORY,
        epgUrl: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String {
        val cap = GatewayConfig.SETUP_BOOTSTRAP_MAX_CHANNELS
        val cappedChannels = if (channels.size <= cap) channels else channels.take(cap)
        val supplementBudget = (cap - cappedChannels.size).coerceAtLeast(0)
        val cappedSupplements = if (supplementBudget <= 0) {
            emptyList()
        } else if (supplements.size <= supplementBudget) {
            supplements
        } else {
            supplements.take(supplementBudget)
        }
        return tivimatePlaylist(
            channels = cappedChannels,
            baseUrl = baseUrl,
            dlhdOrigin = dlhdOrigin,
            logoResolver = logoResolver,
            channelMetaStore = channelMetaStore,
            supplements = cappedSupplements,
            titleStyle = titleStyle,
            epgUrl = epgUrl,
            nowMs = nowMs,
            eventHealthStore = eventHealthStore,
        )
    }

    private data class PlaylistRow(
        val groupOrder: Int,
        val intraGroupSortKey: String = "",
        val intraGroupOrder: Int = 0,
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
        tvgCountryAttr(resolution.countryCode)?.let { attrs += it }
        return attrs.joinToString(" ")
    }

    private fun supplementDisplayTitle(
        supplement: SupplementChannel,
        titleStyle: PlaylistTitleStyle,
        nowMs: Long = System.currentTimeMillis(),
        eventHealthStore: DlhdEventStreamHealthStore? = null,
    ): String {
        val resolution = supplementResolution(supplement)
        val source = supplementTitleSource(supplement, resolution)
        val title = if (supplement.id.startsWith("iptv:") || supplement.id.startsWith("ntv:") ||
            supplement.id.startsWith("adultswim:") || supplement.id.startsWith("sport:") ||
            supplement.id.startsWith("dlhd-guide:") || supplement.id.startsWith("dlhd-event:") ||
            titleStyle == PlaylistTitleStyle.XTREAM_CATEGORY
        ) {
            ChannelTitleNormalizer.supplementDisplayTitle(
                channelName = supplement.name,
                resolution = resolution,
                providerTag = supplement.providerTag,
                style = titleStyle,
                source = source,
                eventSourceUrl = supplement.eventSourceUrl,
                nowMs = nowMs,
            )
        } else {
            escape(supplement.name)
        }
        val labelled = applyFrenchSpecialEventTitleLabel(supplement, title, titleStyle)
        if (!supplement.id.startsWith("dlhd-event:")) return labelled
        val healthStatus = eventHealthStore?.statusForSupplement(supplement)
            ?: DlhdEventStreamHealth.Status.UNKNOWN
        val dotPrefix = EventTitleHealthDots.prefixForSupplement(
            supplement = supplement,
            healthStatus = healthStatus,
            now = Instant.ofEpochMilli(nowMs),
        )
        return if (dotPrefix.isEmpty()) labelled else dotPrefix + labelled
    }

    private fun applyFrenchSpecialEventTitleLabel(
        supplement: SupplementChannel,
        title: String,
        titleStyle: PlaylistTitleStyle,
    ): String {
        if (!isFrenchSpecialEvent(supplement)) return title
        return when (titleStyle) {
            PlaylistTitleStyle.XTREAM_CATEGORY -> applyFrenchXtreamTitleLabel(title)
            PlaylistTitleStyle.LEGACY -> applyFrenchLegacyTitleLabel(title)
        }
    }

    private fun isFrenchSpecialEvent(supplement: SupplementChannel): Boolean {
        if (!supplement.id.startsWith("sport:") &&
            !supplement.id.startsWith("dlhd-guide:") &&
            !supplement.id.startsWith("dlhd-event:")
        ) {
            return false
        }
        return supplementLanguageCode(supplement)?.equals("fr", ignoreCase = true) == true
    }

    private fun applyFrenchXtreamTitleLabel(title: String): String {
        if (title.isBlank()) return title
        var result = title
        if (result.startsWith("US:", ignoreCase = false)) {
            result = "FR:${result.drop(3)}"
        }
        if (result.contains("🇫🇷") || result.contains("[FR]", ignoreCase = true)) {
            return result
        }
        val liveSuffix = " ᴸᴵⱽᴱ"
        return if (result.endsWith(liveSuffix)) {
            result.dropLast(liveSuffix.length) + " 🇫🇷 [FR]$liveSuffix"
        } else {
            "$result 🇫🇷 [FR]"
        }
    }

    private fun applyFrenchLegacyTitleLabel(title: String): String {
        if (title.isBlank()) return title
        if (title.contains("🇫🇷") || title.contains("[FR]", ignoreCase = true)) {
            return title
        }
        return "$title 🇫🇷 [FR]"
    }

    private fun supplementTitleSource(
        supplement: SupplementChannel,
        resolution: GroupTitleResolver.Resolution,
    ): PlaylistTitleSource = when {
        resolution.isAdult -> PlaylistTitleSource.ADULT
        supplement.id.startsWith("adultswim:") -> PlaylistTitleSource.ADULT_SWIM_247
        supplement.id.startsWith("dlhd-guide:") -> PlaylistTitleSource.SPECIAL_EVENT_GUIDE
        supplement.id.startsWith("sport:") || supplement.id.startsWith("dlhd-event:") -> PlaylistTitleSource.SPECIAL_EVENT
        supplement.id.startsWith("iptv:") || supplement.id.startsWith("ntv:") -> PlaylistTitleSource.FAST
        else -> PlaylistTitleSource.SIDECAR
    }

    private fun supplementIntraGroupSortKey(supplement: SupplementChannel): String =
        SpecialEventSort.guideBlockSortKey(supplement)

    private fun supplementIntraGroupSlot(supplement: SupplementChannel): Int =
        SpecialEventSort.supplementIntraSlot(supplement)

    private fun supplementResolution(supplement: SupplementChannel): GroupTitleResolver.Resolution {
        if (supplement.id.startsWith("iptv:")) {
            return GroupTitleResolver.resolve(supplement.name, supplement.tags, supplement.id)
        }
        if (supplement.id.startsWith("adultswim:")) {
            return GroupTitleResolver.Resolution(
                groupTitle = GroupTitleResolver.ENTERTAINMENT,
                categoryLabel = GroupTitleResolver.ENTERTAINMENT,
                countryCode = "US",
                flagEmoji = "🇺🇸",
                isAdult = false,
                appendCountrySuffix = true,
            )
        }
        if (supplement.id.startsWith("sport:") ||
            supplement.id.startsWith("dlhd-guide:") ||
            supplement.id.startsWith("dlhd-event:")
        ) {
            val region = EventRegionMetadata.resolveFromSupplement(supplement)
            return GroupTitleResolver.Resolution(
                groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
                categoryLabel = GroupTitleResolver.SPECIAL_EVENTS,
                countryCode = region.countryCode,
                flagEmoji = region.flagEmoji,
                isAdult = false,
                appendCountrySuffix = true,
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

    private fun channelStreamLine(
        base: String,
        channelId: String,
        dlhdOrigin: String,
        streamUrlStyle: StreamUrlStyle,
    ): String {
        val streamRoute = if (streamUrlStyle == StreamUrlStyle.PLAIN) {
            "stream"
        } else {
            "tivimate-stream"
        }
        val stream = "${base.trimEnd('/')}/$streamRoute/$channelId.m3u8"
        if (streamUrlStyle == StreamUrlStyle.PLAIN) {
            return stream
        }
        val origin = dlhdOrigin.trimEnd('/')
        return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$origin/|Origin=$origin"
    }

    private fun supplementStreamLine(
        supplement: SupplementChannel,
        base: String,
        dlhdOrigin: String,
        streamUrlStyle: StreamUrlStyle,
    ): String {
        if (supplement.id.startsWith("dlhd-guide:")) {
            val slug = supplement.id.removePrefix("dlhd-guide:")
            // TiviMate classifies raw .mp4 as VOD; HLS EVENT wrapper lists guides under Live TV.
            val extension = if (streamUrlStyle == StreamUrlStyle.PLAIN) "mp4" else "m3u8"
            val stream = "${base.trimEnd('/')}/dlhd-event-guide/$slug.$extension"
            return if (streamUrlStyle == StreamUrlStyle.PLAIN) {
                stream
            } else {
                "$stream|User-Agent=$TIVIMATE_USER_AGENT"
            }
        }
        if (supplement.id.startsWith("dlhd-event:")) {
            val token = supplement.dlhdEventKey
                ?: supplement.id.removePrefix("dlhd-event:")
            val stream = "${base.trimEnd('/')}/tivimate-stream/dlhd-event-$token.m3u8"
            if (streamUrlStyle == StreamUrlStyle.PLAIN) {
                return stream
            }
            val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
                ?: DlhdEventStreamResolver.EMBED_REFERER
            val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
                ?: referer.trimEnd('/')
            return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
        }
        if (supplement.id.startsWith("ntv:")) {
            val token = supplement.id.removePrefix("ntv:")
            val stream = "${base.trimEnd('/')}/ntv-stream/$token.m3u8"
            if (streamUrlStyle == StreamUrlStyle.PLAIN) {
                return stream
            }
            val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.REFERER
            val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.ORIGIN
            return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
        }
        val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
        if (referer == null) {
            return supplement.streamUrl
        }
        if (streamUrlStyle == StreamUrlStyle.PLAIN) {
            return supplement.streamUrl
        }
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
        tvgCountryAttr(resolution.countryCode)?.let { attrs += it }
        supplementLanguageCode(supplement)?.let { code ->
            attrs += """tvg-language="${escape(toTvgLanguageCode(code))}""""
        }
        return attrs.joinToString(" ")
    }

    private fun supplementLanguageCode(supplement: SupplementChannel): String? {
        supplement.languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!supplement.id.startsWith("sport:") &&
            !supplement.id.startsWith("dlhd-guide:") &&
            !supplement.id.startsWith("dlhd-event:")
        ) {
            return null
        }
        return SpecialEventLanguageIdentifier.identifyFromSupplement(
            name = supplement.name,
            providerTag = supplement.providerTag,
            eventSourceUrl = supplement.eventSourceUrl,
            streamLabel = streamLabelFromSupplementName(supplement.name),
        )
    }

    private fun streamLabelFromSupplementName(name: String): String? =
        EventLanguageResolver.parseStreamLabel(name)

    private fun toTvgLanguageCode(iso6391: String): String = when (iso6391.trim().lowercase()) {
        "de" -> "deu"
        "it" -> "ita"
        "pt" -> "por"
        else -> EventLanguageResolver.toTvgLanguageCode(iso6391)
    }

    private fun supplementPlaylistGroupTitle(
        supplement: SupplementChannel,
        resolution: GroupTitleResolver.Resolution = supplementResolution(supplement),
    ): String = when {
        supplement.id.startsWith("adultswim:") -> GroupTitleResolver.ENTERTAINMENT
        supplement.id.startsWith("sport:") ||
            supplement.id.startsWith("dlhd-guide:") ||
            supplement.id.startsWith("dlhd-event:") -> GroupTitleResolver.SPECIAL_EVENTS
        supplement.id.startsWith("iptv:") -> resolution.groupTitle
        else -> supplement.groupTitle
    }

    private fun tvgCountryAttr(countryCode: String): String? =
        EventRegionMetadata.tvgCountryAttribute(countryCode)

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
}
