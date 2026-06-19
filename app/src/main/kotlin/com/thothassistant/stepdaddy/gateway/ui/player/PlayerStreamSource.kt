package com.thothassistant.stepdaddy.gateway.ui.player

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.ui.dashboard.TuneChannel
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig

object PlayerStreamSource {
    fun tune(
        exo: ExoPlayer,
        environment: GatewayEnvironment,
        channel: TuneChannel,
        autoplay: Boolean,
    ) {
        val url = PlayerHttpHeaders.streamUrl(environment, channel.id)
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(GatewayConfig.TIVIMATE_USER_AGENT)
            .setDefaultRequestProperties(PlayerHttpHeaders.requestProperties(environment))
        val mediaSource = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = autoplay
    }
}
