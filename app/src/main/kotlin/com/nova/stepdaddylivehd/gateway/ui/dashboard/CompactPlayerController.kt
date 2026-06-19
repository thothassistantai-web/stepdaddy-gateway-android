package com.nova.stepdaddylivehd.gateway.ui.dashboard

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig

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
    private var player: ExoPlayer? = null
    private var channels: List<TuneChannel> = emptyList()
    private var currentIndex: Int = -1

    var playerControlMode: Boolean = false
        private set

    var onControlModeChanged: ((Boolean) -> Unit)? = null

    val currentChannel: TuneChannel?
        get() = channels.getOrNull(currentIndex)

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
        channels = list
        if (currentIndex < 0 && list.isNotEmpty()) {
            tuneToIndex(0, autoplay = false)
        }
    }

    fun tuneTo(channel: TuneChannel, autoplay: Boolean = true) {
        val index = channels.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            tuneToIndex(index, autoplay)
        } else {
            channels = (channels + channel).sortedBy { it.number }
            tuneToIndex(channels.indexOfFirst { it.id == channel.id }, autoplay)
        }
    }

    fun tuneToIndex(index: Int, autoplay: Boolean = true) {
        if (channels.isEmpty()) return
        val safeIndex = index.coerceIn(0, channels.lastIndex)
        currentIndex = safeIndex
        val channel = channels[safeIndex]
        playChannel(channel, autoplay)
        onChannelChanged(channel)
    }

    fun channelUp() {
        if (channels.isEmpty()) return
        val next = if (currentIndex < 0) 0 else (currentIndex + 1) % channels.size
        tuneToIndex(next)
    }

    fun channelDown() {
        if (channels.isEmpty()) return
        val next = if (currentIndex < 0) 0 else {
            if (currentIndex == 0) channels.lastIndex else currentIndex - 1
        }
        tuneToIndex(next)
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
        val base = environment.loopbackBase().trimEnd('/')
        val url = "$base/tivimate-stream/${channel.id}.m3u8"
        val origin = environment.dlhdBaseUrl.trimEnd('/')
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(GatewayConfig.TIVIMATE_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "$origin/",
                    "Origin" to origin,
                ),
            )
        val mediaSource = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = autoplay
    }
}
