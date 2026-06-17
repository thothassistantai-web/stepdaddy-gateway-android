package com.nova.stepdaddylivehd.gateway

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
import com.nova.stepdaddylivehd.gateway.databinding.OverlayServerReadyBinding

/**
 * Brief system overlay for Google TV devices that suppress heads-up notifications and
 * block background activity launches. Requires [Settings.canDrawOverlays].
 */
object GatewayOverlay {
    private const val TAG = "GatewayOverlay"
    private const val DISMISS_MS = 15_000L
    /** Extra chances if the banner was missed on a busy TV launcher or slow screencap. */
    private val RESHOW_DELAYS_MS = longArrayOf(20_000L, 40_000L)

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeRoot: android.view.View? = null
    private var dismissRunnable: Runnable? = null
    private val reshowRunnables = mutableListOf<Runnable>()

    fun showServerReady(context: Context, channelCount: Int, allowReshow: Boolean = true) {
        if (!canDraw(context)) {
            Log.w(TAG, "Overlay permission missing; skipping server-ready banner")
            return
        }
        mainHandler.post {
            runCatching { showInternal(context.applicationContext, channelCount, scheduleReshows = allowReshow) }
                .onFailure { exc -> Log.w(TAG, "Overlay failed: ${exc.message}") }
        }
    }

    fun dismiss() {
        mainHandler.post { dismissActive() }
    }

    private fun dismissActive() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        reshowRunnables.forEach { mainHandler.removeCallbacks(it) }
        reshowRunnables.clear()
        val root = activeRoot ?: return
        activeRoot = null
        runCatching {
            root.context.getSystemService(WindowManager::class.java).removeView(root)
        }
    }

    private fun showInternal(context: Context, channelCount: Int, scheduleReshows: Boolean) {
        dismissActive()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val binding = OverlayServerReadyBinding.inflate(LayoutInflater.from(context))
        binding.textBody.text = context.getString(R.string.alert_server_running_text, channelCount)
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
        Log.i(TAG, "Showing server-ready overlay (channels=$channelCount, reshow=$scheduleReshows)")
        val dismiss = Runnable {
            runCatching { windowManager.removeView(binding.root) }
            if (activeRoot === binding.root) activeRoot = null
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, DISMISS_MS)
        if (scheduleReshows) {
            RESHOW_DELAYS_MS.forEach { delayMs ->
                val reshow = Runnable {
                    if (ServerService.isServiceActive) {
                        showInternal(context, channelCount, scheduleReshows = false)
                    }
                }
                reshowRunnables.add(reshow)
                mainHandler.postDelayed(reshow, delayMs)
            }
        }
    }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)
}
