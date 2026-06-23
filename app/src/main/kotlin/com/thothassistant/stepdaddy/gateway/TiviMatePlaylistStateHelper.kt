package com.thothassistant.stepdaddy.gateway

import android.content.Context
import com.thothassistant.stepdaddy.gateway.model.TiviMatePlayerState
import com.thothassistant.stepdaddy.gateway.R

/** User-facing copy for TiViMate `:4617/state` playlist detection fields. */
object TiviMatePlaylistStateHelper {
    const val REASON_NO_PLAYLIST = "no_playlist"
    const val REASON_WIZARD_INCOMPLETE = "wizard_incomplete"
    const val REASON_GATEWAY_TEST_URL = "gateway_test_url"
    const val REASON_READY = "ready"

    fun summary(context: Context, state: TiviMatePlayerState?): String {
        if (state == null) {
            return context.getString(R.string.tivimate_state_unreachable)
        }
        return when (state.stateReason) {
            REASON_NO_PLAYLIST -> context.getString(R.string.tivimate_state_no_playlist)
            REASON_WIZARD_INCOMPLETE -> context.getString(R.string.tivimate_state_wizard_incomplete)
            REASON_GATEWAY_TEST_URL -> context.getString(
                R.string.tivimate_state_gateway_testing,
                state.playlistUrl ?: state.playlistName.orEmpty(),
            )
            REASON_READY -> context.getString(
                R.string.tivimate_state_ready,
                state.channelCount ?: 0,
            )
            else -> when {
                (state.channelCount ?: 0) > 0 ->
                    context.getString(R.string.tivimate_state_ready, state.channelCount ?: 0)
                (state.playlistCount ?: 0) == 0 ->
                    context.getString(R.string.tivimate_state_no_playlist)
                else -> context.getString(R.string.tivimate_state_unknown)
            }
        }
    }

    fun launchHint(context: Context, state: TiviMatePlayerState?): String? {
        if (state == null) return null
        return when (state.stateReason) {
            REASON_NO_PLAYLIST -> context.getString(R.string.tivimate_launch_hint_no_playlist)
            REASON_GATEWAY_TEST_URL -> context.getString(R.string.tivimate_launch_hint_testing_url)
            REASON_WIZARD_INCOMPLETE -> context.getString(R.string.tivimate_launch_hint_wizard)
            else -> null
        }
    }

    fun aboutPlaylistLine(context: Context, state: TiviMatePlayerState?): String? {
        if (state == null) return null
        val base = summary(context, state)
        val name = state.playlistName?.takeIf { it.isNotBlank() }
        return if (name != null) "$base ($name)" else base
    }

    fun needsPlaylistSetup(state: TiviMatePlayerState?): Boolean {
        if (state == null) return false
        return when (state.stateReason) {
            REASON_NO_PLAYLIST -> true
            REASON_READY, REASON_GATEWAY_TEST_URL, REASON_WIZARD_INCOMPLETE -> false
            else -> (state.playlistCount ?: 0) == 0
        }
    }

    fun isPlaylistReady(state: TiviMatePlayerState?): Boolean =
        state?.stateReason == REASON_READY || (state?.channelCount ?: 0) > 0

    fun shouldDeferBootTune(state: TiviMatePlayerState?): Boolean {
        if (state == null) return true
        if (isPlaylistReady(state)) return false
        return state.stateReason in setOf(
            REASON_NO_PLAYLIST,
            REASON_GATEWAY_TEST_URL,
            REASON_WIZARD_INCOMPLETE,
        )
    }
}
