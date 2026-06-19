package com.nova.stepdaddylivehd.gateway.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.GatewayHealthGate
import com.nova.stepdaddylivehd.gateway.ui.dashboard.GatewayDiagnostics
import com.nova.stepdaddylivehd.gateway.ui.dashboard.TuneChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.ConnectException

class PlayerErrorHandler(
    private val context: Context,
    private val environment: GatewayEnvironment,
    private val scope: CoroutineScope,
    private val onErrorChanged: (PlayerErrorState?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playerListener: Player.Listener? = null
    private var autoRetryCount = 0
    private var lastChannel: TuneChannel? = null
    private var pendingManifestHint: String? = null
    private var lastLoggedKey: String? = null
    private var lastLoggedAtMs = 0L

    private val autoRetryRunnable = Runnable {
        val channel = lastChannel ?: return@Runnable
        onRetryRequested?.invoke(channel)
    }

    var onRetryRequested: ((TuneChannel) -> Unit)? = null

    fun attach(exo: ExoPlayer) {
        detach(exo)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val state = PlayerErrorMapper.fromPlaybackException(error, pendingManifestHint)
                showError(state, lastChannel)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    clearError(resetAutoRetry = true)
                }
            }
        }
        playerListener = listener
        exo.addListener(listener)
    }

    fun detach(exo: ExoPlayer) {
        mainHandler.removeCallbacks(autoRetryRunnable)
        playerListener?.let { exo.removeListener(it) }
        playerListener = null
    }

    fun beginTune(
        channel: TuneChannel,
        autoplay: Boolean,
        skipPreflight: Boolean = false,
        onReady: () -> Unit,
    ) {
        lastChannel = channel
        pendingManifestHint = null
        mainHandler.removeCallbacks(autoRetryRunnable)
        if (!autoplay) {
            clearError(resetAutoRetry = false)
            return
        }
        if (skipPreflight) {
            clearError(resetAutoRetry = false)
            onReady()
            return
        }
        scope.launch {
            if (!GatewayHealthGate.awaitHealthy(context.applicationContext)) {
                mainHandler.post {
                    val offline = PlayerErrorMapper.fromPreflight(
                        httpStatus = null,
                        body = "",
                        throwable = ConnectException("Failed to connect to /127.0.0.1:${environment.port}"),
                    )
                    showError(offline, channel)
                }
                return@launch
            }
            val preflightError = runCatching {
                PlayerManifestPreflight.check(environment, channel)
            }.getOrNull()
            mainHandler.post {
                if (preflightError != null) {
                    pendingManifestHint = preflightError.detail
                    showError(preflightError, channel)
                } else {
                    clearError(resetAutoRetry = false)
                    onReady()
                }
            }
        }
    }

    fun retryCurrent() {
        autoRetryCount = 0
        mainHandler.removeCallbacks(autoRetryRunnable)
        lastChannel?.let { channel ->
            onRetryRequested?.invoke(channel)
        }
    }

    fun clearError(resetAutoRetry: Boolean = true) {
        if (resetAutoRetry) {
            autoRetryCount = 0
        }
        mainHandler.removeCallbacks(autoRetryRunnable)
        pendingManifestHint = null
        onErrorChanged(null)
    }

    private fun showError(state: PlayerErrorState, channel: TuneChannel?) {
        val channelLabel = channel?.let { "${it.number} ${it.name}" } ?: "unknown"
        val logLine = "[$channelLabel] ${state.code}: ${state.detail}"
        val now = System.currentTimeMillis()
        val dedupeKey = logLine
        if (dedupeKey != lastLoggedKey || now - lastLoggedAtMs >= LOG_DEDUPE_MS) {
            lastLoggedKey = dedupeKey
            lastLoggedAtMs = now
            GatewayDiagnostics.error(TAG, logLine)
        }
        onErrorChanged(state)
        scheduleAutoRetryIfNeeded(state)
    }

    private fun scheduleAutoRetryIfNeeded(state: PlayerErrorState) {
        if (!state.recoverable || autoRetryCount >= MAX_AUTO_RETRIES) return
        autoRetryCount += 1
        mainHandler.removeCallbacks(autoRetryRunnable)
        mainHandler.postDelayed(autoRetryRunnable, AUTO_RETRY_DELAY_MS)
    }

    companion object {
        private const val TAG = "PlayerError"
        private const val AUTO_RETRY_DELAY_MS = 3_000L
        private const val MAX_AUTO_RETRIES = 2
        private const val LOG_DEDUPE_MS = 5_000L
    }
}
