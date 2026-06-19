package com.nova.stepdaddylivehd.gateway.ui.dashboard

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerChannelList
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerStreamSource

/**
 * Compact dashboard player with two focus modes (see PLAYER-UX.md):
 * - Browse: D-pad Up/Down scroll the page; Ch+/Ch− buttons change channel.
 * - Control overlay: OK on preview enters; D-pad Up/Down change channel; Back exits.
 */
class CompactPlayerController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val playerView: PlayerView,
    private val onChannelChanged: (TuneChannel) -> Unit,
    private val onFullscreen: (TuneChannel) -> Unit,
) {
    private val channelList = PlayerChannelList()
    private var player: ExoPlayer? = null

    var playerControlMode: Boolean = false
        private set

    var onControlModeChanged: ((Boolean) -> Unit)? = null

    val currentChannel: TuneChannel?
        get() = channelList.currentChannel

    fun attach() {
        if (player != null) return
        val exo = ExoPlayer.Builder(context).build()
        playerView.player = exo
        player = exo
    }

    fun release() {
        if (playerControlMode) {
            exitControlMode()
        }
        playerView.player = null
        player?.release()
        player = null
    }

    fun setChannels(list: List<TuneChannel>) {
        channelList.setChannels(list)
        if (currentChannel == null && list.isNotEmpty()) {
            tuneToIndex(0, autoplay = false)
        }
    }

    fun tuneTo(channel: TuneChannel, autoplay: Boolean = true) {
        val tuned = channelList.tuneTo(channel) ?: return
        playChannel(tuned, autoplay)
        onChannelChanged(tuned)
    }

    fun tuneToIndex(index: Int, autoplay: Boolean = true) {
        val tuned = channelList.tuneToIndex(index) ?: return
        playChannel(tuned, autoplay)
        onChannelChanged(tuned)
    }

    fun channelUp() {
        val tuned = channelList.channelUp() ?: return
        playChannel(tuned, autoplay = true)
        onChannelChanged(tuned)
    }

    fun channelDown() {
        val tuned = channelList.channelDown() ?: return
        playChannel(tuned, autoplay = true)
        onChannelChanged(tuned)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun openFullscreen() {
        currentChannel?.let(onFullscreen)
    }

    fun enterControlMode() {
        if (playerControlMode) return
        playerControlMode = true
        onControlModeChanged?.invoke(true)
    }

    fun exitControlMode() {
        if (!playerControlMode) return
        playerControlMode = false
        onControlModeChanged?.invoke(false)
    }

    /** Browse-mode keys on the video surface: OK opens controls; hardware Ch+/Ch− always tune. */
    fun handlePlayerSurfaceKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                enterControlMode()
                true
            }
            else -> false
        }
    }

    /** Control-mode keys when focus is inside the player tab content. */
    fun handleControlModeKeyEvent(event: KeyEvent): Boolean {
        if (!playerControlMode || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                exitControlMode()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }
            else -> false
        }
    }

    fun installPlayerSurfaceKeyHandler(target: View) {
        target.setOnKeyListener { _, _, event -> handlePlayerSurfaceKeyEvent(event) }
    }

    private fun playChannel(channel: TuneChannel, autoplay: Boolean) {
        val exo = player ?: return
        PlayerStreamSource.tune(exo, environment, channel, autoplay)
    }
}
