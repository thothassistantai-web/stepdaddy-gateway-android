package com.nova.stepdaddylivehd.gateway.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.nova.stepdaddylivehd.gateway.GatewayApp
import com.nova.stepdaddylivehd.gateway.databinding.ActivityPlayerFullscreenBinding
import com.nova.stepdaddylivehd.gateway.ui.dashboard.TuneChannel
import com.nova.stepdaddylivehd.gateway.upstream.GatewayConfig

class PlayerFullscreenActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerFullscreenBinding
    private var player: ExoPlayer? = null
    private var channelId: String = ""
    private var channelName: String = ""
    private var channelNumber: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        channelNumber = intent.getIntExtra(EXTRA_CHANNEL_NUMBER, 0)
        enterImmersive()
        binding.textFullscreenChannel.text = formatOverlay()
        binding.buttonFullscreenBack.setOnClickListener { finish() }
        binding.playerViewFullscreen.requestFocus()
        binding.playerViewFullscreen.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    true
                }
                else -> false
            }
        }
        startPlayback()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    private fun startPlayback() {
        if (channelId.isEmpty()) return
        val environment = (application as GatewayApp).gatewayEnvironment
        val base = environment.loopbackBase().trimEnd('/')
        val url = "$base/tivimate-stream/$channelId.m3u8"
        val origin = environment.dlhdBaseUrl.trimEnd('/')
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(GatewayConfig.TIVIMATE_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "$origin/",
                    "Origin" to origin,
                ),
            )
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerViewFullscreen.player = exo
        val mediaSource = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun releasePlayer() {
        binding.playerViewFullscreen.player = null
        player?.release()
        player = null
    }

    private fun formatOverlay(): String =
        if (channelNumber > 0) {
            "$channelNumber · $channelName"
        } else {
            channelName.ifEmpty { channelId }
        }

    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_NUMBER = "channel_number"

        fun intentExtras(channel: TuneChannel): Bundle =
            Bundle().apply {
                putString(EXTRA_CHANNEL_ID, channel.id)
                putString(EXTRA_CHANNEL_NAME, channel.name)
                putInt(EXTRA_CHANNEL_NUMBER, channel.number)
            }
    }
}
