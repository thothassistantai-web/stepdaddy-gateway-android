package com.nova.stepdaddylivehd.gateway

import android.app.Application
import com.nova.stepdaddylivehd.gateway.epg.EpgChannelMapper
import com.nova.stepdaddylivehd.gateway.epg.EpgManager
import com.nova.stepdaddylivehd.gateway.epg.EpgStore
import com.nova.stepdaddylivehd.gateway.upstream.ChannelMetaStore
import com.nova.stepdaddylivehd.gateway.upstream.LogoResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatewayApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var gatewayEnvironment: GatewayEnvironment
        private set
    lateinit var epgManager: EpgManager
        private set
    lateinit var epgChannelMapper: EpgChannelMapper
        private set
    lateinit var logoResolver: LogoResolver
        private set
    lateinit var channelMetaStore: ChannelMetaStore
        private set

    override fun onCreate() {
        super.onCreate()
        GatewayNotifier.createChannels(this)
        gatewayEnvironment = GatewayEnvironment(this)
        val store = EpgStore(this)
        epgChannelMapper = EpgChannelMapper(this)
        channelMetaStore = ChannelMetaStore(this)
        logoResolver = LogoResolver(this)
        epgManager = EpgManager(store, epgChannelMapper)
        if (gatewayEnvironment.startOnBoot && !GatewayStartHelper.isGatewayHealthy(this@GatewayApp)) {
            appScope.launch {
                GatewayStartHelper.startIfNeeded(this@GatewayApp, "Application", allowReschedule = false)
            }
            GatewayStartHelper.schedulePeriodicEnsureAlive(this)
        }
    }
}
