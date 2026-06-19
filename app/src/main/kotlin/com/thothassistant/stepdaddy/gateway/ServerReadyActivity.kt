package com.thothassistant.stepdaddy.gateway

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.thothassistant.stepdaddy.gateway.databinding.ActivityServerReadyBinding

/**
 * Brief full-screen overlay shown when the gateway starts while another app is in the foreground.
 * Google TV often suppresses heads-up notifications; [GatewayNotifier] triggers this via
 * full-screen intent when [com.thothassistant.stepdaddy.gateway.ui.MainActivity] is not visible.
 */
class ServerReadyActivity : Activity() {
    private val dismissHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        val binding = ActivityServerReadyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 0)
        binding.textBody.text = GatewayOverlay.readyBodyText(this, channelCount)
        dismissHandler.postDelayed({ finish() }, DISMISS_MS)
    }

    override fun onDestroy() {
        dismissHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL_COUNT = "channel_count"
        private const val DISMISS_MS = 15_000L
    }
}
