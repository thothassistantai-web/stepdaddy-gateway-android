package com.thothassistant.stepdaddy.gateway.epg

import java.security.MessageDigest
import kotlin.text.Charsets

object SupplementTvgIdResolver {
    fun forChannelName(nameIndex: IptvOrgNameIndex, channelName: String): String? =
        nameIndex.lookupExact(channelName) ?: nameIndex.lookupFuzzy(channelName)

    fun forSportsEvent(eventUrl: String): String =
        "thetvapp:${shortHash(eventUrl.trim())}"

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
