package com.nova.stepdaddylivehd.gateway.upstream

import com.nova.stepdaddylivehd.gateway.model.Channel
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig.TIVIMATE_USER_AGENT

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
    ): String {
        val base = baseUrl.trimEnd('/')
        val lines = mutableListOf(
            "#EXTM3U url-tvg=\"$base/epg.xml\" x-tvg-url=\"$base/epg.xml\"",
        )
        channels.forEach { channel ->
            lines += "#EXTINF:-1 ${extinfAttrs(channel, base, logoResolver)},${displayTitle(channel)}"
            lines += tivimateStreamLine(base, channel.id, dlhdOrigin)
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun extinfAttrs(channel: Channel, base: String, logoResolver: LogoResolver?): String {
        val attrs = mutableListOf<String>()
        channel.tvgId?.let {
            attrs += """tvg-id="${escape(it)}""""
            attrs += """tvg-name="${escape(channel.name)}""""
        }
        val logo = logoResolver?.resolveLogoUrl(base, channel.name, channel.tvgId)
            ?: channel.logo
            ?: "$base/ui/default-channel.svg"
        attrs += """tvg-logo="${escape(logo)}""""
        attrs += """group-title="${escape("Live TV")}""""
        attrs += """tvg-chno="${escape(channel.id)}""""
        return attrs.joinToString(" ")
    }

    private fun displayTitle(channel: Channel): String {
        return channel.name.replace("\"", "'")
    }

    private fun tivimateStreamLine(base: String, channelId: String, dlhdOrigin: String): String {
        val stream = "${base.trimEnd('/')}/tivimate-stream/$channelId.m3u8"
        val origin = dlhdOrigin.trimEnd('/')
        return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$origin/|Origin=$origin"
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
}
