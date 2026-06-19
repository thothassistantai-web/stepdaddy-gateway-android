package com.thothassistant.stepdaddy.gateway

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.thothassistant.stepdaddy.gateway.databinding.OverlayServerReadyBinding

/**
 * Brief system overlay for Google TV devices that suppress heads-up notifications and
 * block background activity launches. Requires [Settings.canDrawOverlays].
 */
object GatewayOverlay {
    private const val TAG = "GatewayOverlay"
    private const val DISMISS_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeRoot: android.view.View? = null
    private var dismissRunnable: Runnable? = null

    fun showServerReady(context: Context, channelCount: Int) {
        if (!canDraw(context)) {
            Log.w(TAG, "Overlay permission missing; skipping server-ready banner")
            return
        }
        mainHandler.post {
            runCatching { showInternal(context.applicationContext, channelCount) }
                .onFailure { exc -> Log.w(TAG, "Overlay failed: ${exc.message}") }
        }
    }

    fun dismiss() {
        mainHandler.post { dismissActive() }
    }

    private fun dismissActive() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val root = activeRoot ?: return
        activeRoot = null
        runCatching {
            root.context.getSystemService(WindowManager::class.java).removeView(root)
        }
    }

    private fun showInternal(context: Context, channelCount: Int) {
        dismissActive()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val binding = OverlayServerReadyBinding.inflate(LayoutInflater.from(context))
        binding.textBody.text = readyBodyText(context, channelCount)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 48
        }
        activeRoot = binding.root
        windowManager.addView(binding.root, params)
        Log.i(TAG, "Showing server-ready overlay (channels=$channelCount)")
        val dismiss = Runnable {
            runCatching { windowManager.removeView(binding.root) }
            if (activeRoot === binding.root) activeRoot = null
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, DISMISS_MS)
    }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    internal fun readyBodyText(context: Context, channelCount: Int): String =
        if (channelCount > 0) {
            context.getString(R.string.alert_server_running_text, channelCount)
        } else {
            context.getString(R.string.alert_server_running_loading)
        }
}
