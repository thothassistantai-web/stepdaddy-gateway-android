package com.nova.stepdaddylivehd.gateway.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.ui.dashboard.ChannelHistoryStore
import com.nova.stepdaddylivehd.gateway.ui.dashboard.TuneChannel

class FullscreenPlayerController(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val playerView: PlayerView,
    private val historyStore: ChannelHistoryStore,
    private val onUiChanged: (UiState) -> Unit,
) {
    data class UiState(
        val channel: TuneChannel?,
        val overlayVisible: Boolean,
        val infoBarVisible: Boolean,
        val playing: Boolean,
    )

    private val channelList = PlayerChannelList()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var overlayVisible = false
    private var infoBarPinned = false
    private var infoBarFlashActive = false

    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideInfoBarRunnable = Runnable {
        infoBarFlashActive = false
        publishUi()
    }

    val currentChannel: TuneChannel?
        get() = channelList.currentChannel

    fun attach() {
        if (player != null) return
        val exo = ExoPlayer.Builder(context).build()
        playerView.player = exo
        player = exo
        publishUi()
    }

    fun release() {
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.removeCallbacks(hideInfoBarRunnable)
        playerView.player = null
        player?.release()
        player = null
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

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun isOverlayVisible(): Boolean = overlayVisible

    fun showOverlay() {
        overlayVisible = true
        scheduleOverlayAutoHide()
        publishUi()
    }

    fun hideOverlay() {
        overlayVisible = false
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (!infoBarPinned) {
            infoBarFlashActive = false
            mainHandler.removeCallbacks(hideInfoBarRunnable)
        }
        publishUi()
    }

    fun toggleOverlay() {
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
        if (overlayVisible) {
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_AUTO_HIDE_MS)
        }
    }

    private fun playChannel(channel: TuneChannel, autoplay: Boolean) {
        val exo = player ?: return
        PlayerStreamSource.tune(exo, environment, channel, autoplay)
    }

    private fun publishUi() {
        val infoVisible = overlayVisible || infoBarPinned || infoBarFlashActive
        onUiChanged(
            UiState(
                channel = channelList.currentChannel,
                overlayVisible = overlayVisible,
                infoBarVisible = infoVisible,
                playing = isPlaying(),
            ),
        )
    }

    companion object {
        const val OVERLAY_AUTO_HIDE_MS = 4_000L
    }
}
