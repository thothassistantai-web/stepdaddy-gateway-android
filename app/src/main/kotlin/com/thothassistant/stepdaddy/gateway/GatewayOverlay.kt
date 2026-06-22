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
import android.widget.TextView
import com.thothassistant.stepdaddy.gateway.databinding.OverlayGatewayToastBinding

/**
 * Compact bottom overlay for cross-app HUD toasts (TiviMate, Google TV home).
 * Requires [Settings.canDrawOverlays].
 */
object GatewayOverlay {
    private const val TAG = "GatewayOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeRoot: android.view.View? = null
    private var dismissRunnable: Runnable? = null

    fun showToast(context: Context, message: String, durationMs: Long) {
        if (!canDraw(context)) {
            Log.w(TAG, "Overlay permission missing; skipping HUD toast")
            return
        }
        mainHandler.post {
            runCatching { showInternal(context.applicationContext, message, durationMs) }
                .onFailure { exc -> Log.w(TAG, "Overlay failed: ${exc.message}") }
        }
    }

    /** @deprecated Use [GatewayHud.readyMessage]; kept for notification body text. */
    fun readyBodyText(context: Context, channelCount: Int): String =
        GatewayHud.readyMessage(context, channelCount)

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

    private fun showInternal(context: Context, message: String, durationMs: Long) {
        dismissActive()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val binding = OverlayGatewayToastBinding.inflate(LayoutInflater.from(context))
        binding.textHudMessage.text = message
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        activeRoot = binding.root
        windowManager.addView(binding.root, params)
        Log.i(TAG, "HUD toast: $message")
        val dismiss = Runnable {
            runCatching { windowManager.removeView(binding.root) }
            if (activeRoot === binding.root) activeRoot = null
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, durationMs)
    }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)
}
