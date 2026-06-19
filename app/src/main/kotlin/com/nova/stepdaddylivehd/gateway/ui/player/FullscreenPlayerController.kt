package com.nova.stepdaddylivehd.gateway.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.ui.dashboard.ChannelHistoryStore
import com.nova.stepdaddylivehd.gateway.ui.dashboard.TuneChannel
import kotlinx.coroutines.CoroutineScope

class FullscreenPlayerController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val scope: CoroutineScope,
    private val playerView: PlayerView,
    private val historyStore: ChannelHistoryStore,
    private val onUiChanged: (UiState) -> Unit,
) {
    data class UiState(
        val channel: TuneChannel?,
        val overlayVisible: Boolean,
        val infoBarVisible: Boolean,
        val playing: Boolean,
        val error: PlayerErrorState?,
    )

    private val channelList = PlayerChannelList()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var overlayVisible = false
    private var infoBarPinned = false
    private var infoBarFlashActive = false
    private var errorState: PlayerErrorState? = null
    private lateinit var errorHandler: PlayerErrorHandler

    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideInfoBarRunnable = Runnable {
        infoBarFlashActive = false
        publishUi()
    }

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
                if (state != null) {
                    showOverlay()
                }
                publishUi()
            },
        )
        errorHandler.onRetryRequested = { channel ->
            playChannel(channel, autoplay = true, skipPreflight = false)
        }
        val exo = ExoPlayer.Builder(context).build()
        playerView.player = exo
        player = exo
        errorHandler.attach(exo)
        publishUi()
    }

    fun release() {
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.removeCallbacks(hideInfoBarRunnable)
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
    }

    fun tuneTo(channel: TuneChannel, autoplay: Boolean = true) {
        val tuned = channelList.tuneTo(channel) ?: return
        playChannel(tuned, autoplay)
        historyStore.record(tuned)
        flashInfoBar()
        publishUi()
    }

    fun tuneToIndex(index: Int, autoplay: Boolean = true) {
        val tuned = channelList.tuneToIndex(index) ?: return
        playChannel(tuned, autoplay)
        historyStore.record(tuned)
        flashInfoBar()
        publishUi()
    }

    fun channelUp() {
        val tuned = channelList.channelUp() ?: return
        playChannel(tuned, autoplay = true)
        historyStore.record(tuned)
        flashInfoBar()
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun channelDown() {
        val tuned = channelList.channelDown() ?: return
        playChannel(tuned, autoplay = true)
        historyStore.record(tuned)
        flashInfoBar()
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun retryCurrentChannel() {
        errorHandler.retryCurrent()
    }

    fun nextChannelAfterError() {
        channelDown()
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun isOverlayVisible(): Boolean = overlayVisible || errorState != null

    fun showOverlay() {
        overlayVisible = true
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun hideOverlay() {
        if (errorState != null) {
            publishUi()
            return
        }
        overlayVisible = false
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (!infoBarPinned) {
            infoBarFlashActive = false
            mainHandler.removeCallbacks(hideInfoBarRunnable)
        }
        publishUi()
    }

    fun toggleOverlay() {
        if (errorState != null) {
            showOverlay()
            return
        }
        if (overlayVisible) hideOverlay() else showOverlay()
    }

    fun toggleInfoBar() {
        infoBarPinned = !infoBarPinned
        if (infoBarPinned) {
            infoBarFlashActive = false
            mainHandler.removeCallbacks(hideInfoBarRunnable)
        } else if (!overlayVisible) {
            infoBarFlashActive = false
            mainHandler.removeCallbacks(hideInfoBarRunnable)
        }
        publishUi()
    }

    private fun flashInfoBar() {
        if (overlayVisible || infoBarPinned) return
        infoBarFlashActive = true
        mainHandler.removeCallbacks(hideInfoBarRunnable)
        mainHandler.postDelayed(hideInfoBarRunnable, OVERLAY_AUTO_HIDE_MS)
        publishUi()
    }

    private fun scheduleOverlayAutoHide() {
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (overlayVisible && errorState == null) {
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_AUTO_HIDE_MS)
        }
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

    private fun publishUi() {
        val infoVisible = overlayVisible || infoBarPinned || infoBarFlashActive || errorState != null
        onUiChanged(
            UiState(
                channel = channelList.currentChannel,
                overlayVisible = overlayVisible || errorState != null,
                infoBarVisible = infoVisible,
                playing = isPlaying(),
                error = errorState,
            ),
        )
    }

    companion object {
        const val OVERLAY_AUTO_HIDE_MS = 4_000L
    }
}
