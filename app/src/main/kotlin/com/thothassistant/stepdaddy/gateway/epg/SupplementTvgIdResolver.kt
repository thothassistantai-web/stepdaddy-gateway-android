package com.thothassistant.stepdaddy.gateway.epg

object SupplementTvgIdResolver {
    fun forChannelName(nameIndex: IptvOrgNameIndex, channelName: String): String? =
        nameIndex.lookupExact(channelName) ?: nameIndex.lookupFuzzy(channelName)
}
