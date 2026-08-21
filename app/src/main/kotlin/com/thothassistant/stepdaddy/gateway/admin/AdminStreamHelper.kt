package com.thothassistant.stepdaddy.gateway.admin

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.upstream.DuloCxLiveConfig
import com.thothassistant.stepdaddy.gateway.upstream.NtvCxCdnLiveConfig
import java.net.URI

object AdminStreamHelper {
    private const val TIVIMATE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 Chrome/91.0.4472.120 Mobile Safari/537.36"

    fun daddylivePlayUrl(base: String, channelId: String, dlhdOrigin: String): String {
        val stream = "${base.trimEnd('/')}/tivimate-stream/$channelId.m3u8"
        val origin = dlhdOrigin.trimEnd('/')
        return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$origin/|Origin=$origin"
    }

    fun supplementPlayUrl(base: String, supplement: SupplementChannel): String {
        if (supplement.id.startsWith("ntv:")) {
            val token = supplement.id.removePrefix("ntv:")
            val stream = "${base.trimEnd('/')}/ntv-stream/$token.m3u8"
            val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.REFERER
            val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
                ?: NtvCxCdnLiveConfig.ORIGIN
            return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
        }
        if (supplement.id.startsWith(DuloCxLiveConfig.ID_PREFIX)) {
            val uuid = supplement.duloChannelId?.trim().orEmpty()
                .ifEmpty { supplement.id.removePrefix(DuloCxLiveConfig.ID_PREFIX) }
            val stream = "${base.trimEnd('/')}/dulo-stream/$uuid.m3u8"
            val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
                ?: DuloCxLiveConfig.REFERER
            val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() }
                ?: DuloCxLiveConfig.ORIGIN
            return "$stream|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
        }
        val referer = supplement.referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: return supplement.streamUrl
        val origin = supplement.origin?.trim()?.takeIf { it.isNotEmpty() } ?: referer.trimEnd('/')
        return "${supplement.streamUrl}|User-Agent=$TIVIMATE_USER_AGENT|Referer=$referer|Origin=$origin"
    }

    fun dlhdOrigin(dlhdBaseUrl: String): String = runCatching {
        URI(dlhdBaseUrl.trim()).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(dlhdBaseUrl.trimEnd('/'))
}
