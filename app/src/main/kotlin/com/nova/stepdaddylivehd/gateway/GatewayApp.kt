package com.nova.stepdaddylivehd.gateway

import android.app.Application
import com.nova.stepdaddylivehd.gateway.epg.EpgChannelMapper
import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.epg.EpgStore
import com.nova.stepdaddylivehd.gateway.upstream.LogoResolver

class GatewayApp : Application() {
    lateinit var gatewayEnvironment: GatewayEnvironment
        private set
    lateinit var epgManager: EpgManager
        private set
    lateinit var epgChannelMapper: EpgChannelMapper
        private set
    lateinit var logoResolver: LogoResolver
        private set

    override fun onCreate() {
        super.onCreate()
        GatewayNotifier.createChannels(this)
        gatewayEnvironment = GatewayEnvironment(this)
        val store = EpgStore(this)
        epgChannelMapper = EpgChannelMapper(this)
        logoResolver = LogoResolver(this)
        epgManager = EpgManager(store, epgChannelMapper)
        if (gatewayEnvironment.startOnBoot && !ServerService.isServiceActive) {
            GatewayStartHelper.startIfNeeded(this, "Application", allowReschedule = false)
        }
    }
}
