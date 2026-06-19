package com.thothassistant.stepdaddy.gateway.ui.player

import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.R

class PlayerErrorOverlay(
    root: View,
    private val onRetry: () -> Unit,
    private val onNextChannel: () -> Unit,
) {
    private val overlay: View = root.findViewById(R.id.playerErrorOverlay)
    private val titleView: TextView = root.findViewById(R.id.textPlayerErrorTitle)
    private val detailView: TextView = root.findViewById(R.id.textPlayerErrorDetail)
    private val codeView: TextView = root.findViewById(R.id.textPlayerErrorCode)
    private val retryButton: MaterialButton = root.findViewById(R.id.buttonPlayerErrorRetry)
    private val nextButton: MaterialButton = root.findViewById(R.id.buttonPlayerErrorNext)

    val isVisible: Boolean
        get() = overlay.visibility == View.VISIBLE

    init {
        retryButton.setOnClickListener { onRetry() }
        nextButton.setOnClickListener { onNextChannel() }
        retryButton.nextFocusRightId = nextButton.id
        nextButton.nextFocusLeftId = retryButton.id
    }

    fun bind(state: PlayerErrorState?) {
        if (state == null) {
            overlay.visibility = View.GONE
            return
        }
        titleView.text = state.title
        detailView.text = state.detail
        codeView.text = state.code
        overlay.visibility = View.VISIBLE
    }

    fun requestInitialFocus() {
        if (isVisible) {
            retryButton.requestFocus()
        }
    }

    fun isErrorButtonFocused(focused: View?): Boolean =
        focused === retryButton || focused === nextButton
}
