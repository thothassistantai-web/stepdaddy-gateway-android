package com.thothassistant.stepdaddy.gateway.upstream

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Filters TVApp2 supplement entries before dedup/playlist merge.
 *
 * TheTvApp linear-TV URLs and TVApp2 token proxies are blocked (dead token flow on
 * thetvapp.link). Only direct HLS hosts (e.g. MoveOnJoy) are allowed from the sidecar.
 */
object SupplementProviderFilter {
    enum class Provider {
        MOVEONJOY,
        THETVAPP,
        TVPASS,
        TOKEN_PROXY,
        UNKNOWN,
    }

    data class Result(
        val allowed: List<M3uParser.Entry>,
        val blockedTheTvApp: Int = 0,
        val blockedTvPass: Int = 0,
        val blockedTokenProxy: Int = 0,
        val blockedUnknown: Int = 0,
    ) {
        val blockedTotal: Int
            get() = blockedTheTvApp + blockedTvPass + blockedTokenProxy + blockedUnknown
    }

    fun filter(entries: List<M3uParser.Entry>): Result {
        val allowed = mutableListOf<M3uParser.Entry>()
        var blockedTheTvApp = 0
        var blockedTvPass = 0
        var blockedTokenProxy = 0
        var blockedUnknown = 0
        for (entry in entries) {
            when (classify(entry)) {
                Provider.MOVEONJOY -> allowed += entry
                Provider.THETVAPP -> blockedTheTvApp++
                Provider.TVPASS -> blockedTvPass++
                Provider.TOKEN_PROXY -> blockedTokenProxy++
                Provider.UNKNOWN -> blockedUnknown++
            }
        }
        return Result(
            allowed = allowed,
            blockedTheTvApp = blockedTheTvApp,
            blockedTvPass = blockedTvPass,
            blockedTokenProxy = blockedTokenProxy,
            blockedUnknown = blockedUnknown,
        )
    }

    fun classify(entry: M3uParser.Entry): Provider {
        val url = entry.streamUrl.trim().lowercase()
        val decodedProxy = decodeChannelProxyTarget(url)
        val target = decodedProxy ?: url
        if (target.contains("thetvapp.to") || target.contains("thetvapp.link")) {
            return Provider.THETVAPP
        }
        if (url.contains("channel?url=") || decodedProxy != null) {
            return when {
                target.contains("tvpass.org") -> Provider.TVPASS
                target.contains("thetvapp") -> Provider.THETVAPP
                else -> Provider.TOKEN_PROXY
            }
        }
        if (target.contains("moveonjoy.com") && target.contains(".m3u8")) {
            return Provider.MOVEONJOY
        }
        return Provider.UNKNOWN
    }

    fun isAllowed(entry: M3uParser.Entry): Boolean = classify(entry) == Provider.MOVEONJOY

    private fun decodeChannelProxyTarget(streamUrl: String): String? {
        val marker = "channel?url="
        val idx = streamUrl.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val encoded = streamUrl.substring(idx + marker.length).substringBefore('|').trim()
        if (encoded.isEmpty()) return null
        return runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()).lowercase()
        }.getOrNull()
    }
}
