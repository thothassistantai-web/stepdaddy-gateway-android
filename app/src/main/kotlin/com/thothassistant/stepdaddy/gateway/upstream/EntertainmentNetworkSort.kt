package com.thothassistant.stepdaddy.gateway.upstream

/** @see CategoryNetworkSort */
object EntertainmentNetworkSort {
    fun countrySortKey(countryCode: String): String =
        CategoryNetworkSort.countrySortKey(countryCode)

    fun familyKey(channelName: String): String =
        CategoryNetworkSort.familyKey(GroupTitleResolver.ENTERTAINMENT, channelName)

    fun normalize(channelName: String): String =
        CategoryNetworkSort.normalize(channelName)
}
