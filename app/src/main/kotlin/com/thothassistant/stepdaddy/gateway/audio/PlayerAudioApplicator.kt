package com.thothassistant.stepdaddy.gateway.audio

import androidx.media3.common.Player
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment

/** Applies gateway audio prefs to an embedded ExoPlayer instance. */
object PlayerAudioApplicator {
    fun apply(player: Player, environment: GatewayEnvironment) {
        player.volume = AudioPlaybackSettings.embeddedLinearVolume(environment)
    }
}
