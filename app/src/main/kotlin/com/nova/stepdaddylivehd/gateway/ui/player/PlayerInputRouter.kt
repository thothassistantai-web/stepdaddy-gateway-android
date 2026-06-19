package com.nova.stepdaddylivehd.gateway.ui.player

import android.view.KeyEvent

/**
 * Central key dispatch for fullscreen playback with clear precedence (see PLAYER-UX.md).
 */
class PlayerInputRouter(
    private val isTvDevice: Boolean,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onExitFullscreen()
        fun onChannelUp()
        fun onChannelDown()
        fun onToggleOverlay()
        fun onShowOverlay()
        fun onTogglePlayPause()
        fun onToggleInfoBar()
        fun isOverlayVisible(): Boolean
        fun isOverlayButtonFocused(): Boolean
        fun onActivateFocusedButton(): Boolean
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                callbacks.onExitFullscreen()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                callbacks.onChannelUp()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                callbacks.onChannelDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> handleVerticalDpad(up = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> handleVerticalDpad(up = false)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> handleHorizontalDpad()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> handleCenterKey()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                callbacks.onTogglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_TV -> {
                callbacks.onToggleInfoBar()
                true
            }
            else -> false
        }
    }

    private fun handleVerticalDpad(up: Boolean): Boolean {
        if (callbacks.isOverlayVisible()) {
            if (up) callbacks.onChannelUp() else callbacks.onChannelDown()
            return true
        }
        if (isTvDevice) {
            callbacks.onShowOverlay()
            return true
        }
        return false
    }

    private fun handleHorizontalDpad(): Boolean {
        if (!callbacks.isOverlayVisible() && isTvDevice) {
            callbacks.onShowOverlay()
            return true
        }
        // Live IPTV: no seek on left/right.
        return callbacks.isOverlayVisible()
    }

    private fun handleCenterKey(): Boolean {
        if (callbacks.isOverlayVisible() && callbacks.isOverlayButtonFocused()) {
            return callbacks.onActivateFocusedButton()
        }
        callbacks.onToggleOverlay()
        return true
    }
}
