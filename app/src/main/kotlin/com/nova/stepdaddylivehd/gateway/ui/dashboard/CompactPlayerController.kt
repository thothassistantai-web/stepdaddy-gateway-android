package com.nova.stepdaddylivehd.gateway.ui.dashboard

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerChannelList
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerErrorHandler
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerErrorState
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerStreamSource
import kotlinx.coroutines.CoroutineScope

/**
 * Compact dashboard player with two focus modes (see PLAYER-UX.md):
 * - Browse: D-pad Up/Down scroll the page; Ch+/Ch− buttons change channel.
 * - Control overlay: OK on preview enters; D-pad Up/Down change channel; Back exits.
 */
class CompactPlayerController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val scope: CoroutineScope,
    private val playerView: PlayerView,
    private val onChannelChanged: (TuneChannel) -> Unit,
    private val onFullscreen: (TuneChannel) -> Unit,
) {
    private val channelList = PlayerChannelList()
    private var player: ExoPlayer? = null
    private var errorState: PlayerErrorState? = null
    private lateinit var errorHandler: PlayerErrorHandler

    var playerControlMode: Boolean = false
        private set

    var onControlModeChanged: ((Boolean) -> Unit)? = null
    var onErrorStateChanged: ((PlayerErrorState?) -> Unit)? = null

    val currentChannel: TuneChannel?
        get() = channelList.currentChannel

    val hasPlaybackError: Boolean
        get() = errorState != null

    fun attach() {
        if (player != null) return
        errorHandler = PlayerErrorHandler(
            context = context,
            environment = environment,
            scope = scope,
            onErrorChanged = { state ->
                errorState = state
                onErrorStateChanged?.invoke(state)
            },
        )
        errorHandler.onRetryRequested = { channel ->
            playChannel(channel, autoplay = true, skipPreflight = false)
        }
        val exo = ExoPlayer.Builder(context).build()
        playerView.player = exo
        player = exo
        errorHandler.attach(exo)
    }

    fun release() {
        if (playerControlMode) {
            exitControlMode()
        }
        if (::errorHandler.isInitialized) {
            player?.let { errorHandler.detach(it) }
        }
        playerView.player = null
        player?.release()
        player = null
        errorState = null
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

    fun retryCurrentChannel() {
        errorHandler.retryCurrent()
    }

    fun nextChannelAfterError() {
        channelDown()
    }

    fun togglePlayPause() {
        val exo = player ?: return
        val channel = currentChannel
        if (!exo.isPlaying &&
            (exo.playbackState == Player.STATE_IDLE || exo.mediaItemCount == 0) &&
            channel != null
        ) {
            playChannel(channel, autoplay = true)
            return
        }
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    fun resumePlayback() {
        val exo = player ?: return
        if (exo.mediaItemCount > 0 && !exo.isPlaying) {
            exo.play()
        }
    }

    fun hasLoadedMedia(): Boolean = (player?.mediaItemCount ?: 0) > 0

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

    private fun playChannel(
        channel: TuneChannel,
        autoplay: Boolean,
        skipPreflight: Boolean = false,
    ) {
        val exo = player ?: return
        errorHandler.beginTune(channel, autoplay = autoplay, skipPreflight = skipPreflight) {
            PlayerStreamSource.tune(exo, environment, channel, autoplay)
        }
    }
}
