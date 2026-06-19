package com.thothassistant.stepdaddy.gateway.ui

import com.thothassistant.stepdaddy.gateway.network.LanAddressResolver

object LocalNetworkHelper {
    fun lanIpv4(): String? = LanAddressResolver.lanIpv4()
}
