package com.nova.stepdaddylivehd.gateway.ui.dashboard

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.ScrollView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import com.nova.stepdaddylivehd.gateway.GatewayHealthGate
import com.nova.stepdaddylivehd.gateway.R
import com.nova.stepdaddylivehd.gateway.ui.PlayerFullscreenActivity
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerErrorOverlay
import com.nova.stepdaddylivehd.gateway.ui.player.PlayerErrorState
import androidx.media3.ui.PlayerView

class DashboardBottomPanel(
    private val activity: AppCompatActivity,
    root: View,
    private val environment: GatewayEnvironment,
    private val scope: LifecycleCoroutineScope,
) {
    private val panelRoot: View = root.findViewById(R.id.bottomPanelRoot)
    private val tabMessages: MaterialButton = root.findViewById(R.id.tabMessages)
    private val tabErrorLogs: MaterialButton = root.findViewById(R.id.tabErrorLogs)
    private val tabHistory: MaterialButton = root.findViewById(R.id.tabHistory)
    private val tabPlayer: MaterialButton = root.findViewById(R.id.tabPlayer)
    private val contentMessages: View = root.findViewById(R.id.contentMessages)
    private val contentErrorLogs: View = root.findViewById(R.id.contentErrorLogs)
    private val contentHistory: View = root.findViewById(R.id.contentHistory)
    private val contentPlayer: View = root.findViewById(R.id.contentPlayer)
    private val textMessages: TextView = root.findViewById(R.id.textMessages)
    private val scrollMessages: ScrollView = root.findViewById(R.id.contentMessages)
    private val textErrorLogs: TextView = root.findViewById(R.id.textErrorLogs)
    private val scrollErrorLogs: ScrollView = root.findViewById(R.id.scrollErrorLogs)
    private val buttonRefreshLogs: MaterialButton = root.findViewById(R.id.buttonRefreshLogs)
    private val recyclerHistory: RecyclerView = root.findViewById(R.id.recyclerHistory)
    private val textHistoryEmpty: TextView = root.findViewById(R.id.textHistoryEmpty)
    private val playerView: PlayerView = root.findViewById(R.id.playerViewCompact)
    private val textPlayerChannel: TextView = root.findViewById(R.id.textPlayerChannel)
    private val buttonPlayerControls: MaterialButton = root.findViewById(R.id.buttonPlayerControls)
    private val playerControlOverlay: View = root.findViewById(R.id.playerControlOverlay)
    private val buttonPlayerChDown: MaterialButton = root.findViewById(R.id.buttonPlayerChDown)
    private val buttonPlayerPlayPause: MaterialButton = root.findViewById(R.id.buttonPlayerChPlay)
    private val buttonPlayerChUp: MaterialButton = root.findViewById(R.id.buttonPlayerChUp)
    private val buttonPlayerFullscreen: MaterialButton = root.findViewById(R.id.buttonPlayerFullscreen)
    private val buttonOverlayChDown: MaterialButton = root.findViewById(R.id.buttonOverlayChDown)
    private val buttonOverlayChPlay: MaterialButton = root.findViewById(R.id.buttonOverlayChPlay)
    private val buttonOverlayChUp: MaterialButton = root.findViewById(R.id.buttonOverlayChUp)
    private val buttonOverlayFullscreen: MaterialButton = root.findViewById(R.id.buttonOverlayFullscreen)
    private val playerVideoContainer: View = root.findViewById(R.id.playerVideoContainer)

    private val historyStore = ChannelHistoryStore(activity)
    private val historyAdapter = HistoryAdapter { entry ->
        selectTab(Tab.HISTORY)
        playerController.tuneTo(
            TuneChannel(entry.channelId, entry.name, entry.number),
        )
        selectTab(Tab.PLAYER)
        buttonPlayerChDown.requestFocus()
    }
    private lateinit var playerController: CompactPlayerController
    private lateinit var playerErrorOverlay: PlayerErrorOverlay

    private val messageListener: (List<GatewayMessage>) -> Unit = { messages ->
        activity.runOnUiThread { renderMessages(messages) }
    }
    private val logListener: (List<GatewayLogLine>) -> Unit = { lines ->
        activity.runOnUiThread { renderErrorLogs(lines) }
    }
    private val historyListener: (List<ChannelHistoryEntry>) -> Unit = { entries ->
        activity.runOnUiThread { renderHistory(entries) }
    }

    private var activeTab: Tab = Tab.MESSAGES
    private var channelsLoaded = false

    fun attach() {
        playerErrorOverlay = PlayerErrorOverlay(
            root = playerVideoContainer,
            onRetry = { playerController.retryCurrentChannel() },
            onNextChannel = { playerController.nextChannelAfterError() },
        )
        playerController = CompactPlayerController(
            context = activity,
            environment = environment,
            scope = scope,
            playerView = playerView,
            onChannelChanged = { channel ->
                textPlayerChannel.text = formatChannelLabel(channel)
                historyStore.record(channel)
            },
            onFullscreen = { channel -> launchFullscreen(channel) },
        )
        playerController.onControlModeChanged = { active -> updateControlModeUi(active) }
        playerController.onErrorStateChanged = { state -> renderPlayerError(state) }
        playerController.attach()

        recyclerHistory.layoutManager = LinearLayoutManager(activity)
        recyclerHistory.adapter = historyAdapter

        tabMessages.setOnClickListener { selectTab(Tab.MESSAGES) }
        tabErrorLogs.setOnClickListener { selectTab(Tab.ERROR_LOGS) }
        tabHistory.setOnClickListener { selectTab(Tab.HISTORY) }
        tabPlayer.setOnClickListener { selectTab(Tab.PLAYER) }

        buttonRefreshLogs.setOnClickListener {
            GatewayLogRing.refreshFromLogcat()
        }
        wirePlayerButtons(
            buttonPlayerChDown,
            buttonPlayerPlayPause,
            buttonPlayerChUp,
            buttonPlayerFullscreen,
        )
        wirePlayerButtons(
            buttonOverlayChDown,
            buttonOverlayChPlay,
            buttonOverlayChUp,
            buttonOverlayFullscreen,
        )
        buttonPlayerControls.setOnClickListener { playerController.enterControlMode() }

        playerController.installPlayerSurfaceKeyHandler(playerView)
        buttonPlayerControls.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                playerController.enterControlMode()
                true
            } else {
                false
            }
        }

        wireTabFocus()
        selectTab(Tab.MESSAGES)
        GatewayMessageBus.addListener(messageListener)
        GatewayLogRing.addListener(logListener)
        historyStore.addListener(historyListener)
        loadChannelsIfNeeded()
    }

    fun onResume() {
        playerController.attach()
        loadChannelsIfNeeded()
    }

    fun onPause() {
        playerController.release()
    }

    fun onDestroy() {
        GatewayMessageBus.removeListener(messageListener)
        GatewayLogRing.removeListener(logListener)
        historyStore.removeListener(historyListener)
        playerController.release()
    }

    fun tuneFromHistory(channel: TuneChannel) {
        selectTab(Tab.PLAYER)
        playerController.tuneTo(channel)
        buttonPlayerChDown.requestFocus()
    }

    /**
     * Activity-level key dispatch: Back releases log/history focus; Player tab channel keys.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (releaseFocusFromBrowseContent()) return true
        }
        if (activeTab != Tab.PLAYER || event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                playerController.channelUp()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                playerController.channelDown()
                return true
            }
        }
        if (!playerController.playerControlMode || !isFocusInPlayerContent()) return false
        return playerController.handleControlModeKeyEvent(event)
    }

    private fun loadChannelsIfNeeded() {
        if (channelsLoaded) return
        scope.launch {
            if (!GatewayHealthGate.awaitHealthy(activity)) return@launch
            val channels = ChannelListProvider.loadSorted(environment)
            if (channels.isEmpty()) return@launch
            channelsLoaded = true
            playerController.setChannels(channels)
            historyStore.lastChannel()?.let { last ->
                playerController.tuneTo(last, autoplay = false)
            } ?: playerController.tuneToIndex(0, autoplay = false)
        }
    }

    private fun launchFullscreen(channel: TuneChannel) {
        val intent = Intent(activity, PlayerFullscreenActivity::class.java).apply {
            putExtras(PlayerFullscreenActivity.intentExtras(channel))
        }
        activity.startActivity(intent)
    }

    private fun selectTab(tab: Tab) {
        if (activeTab == Tab.PLAYER && tab != Tab.PLAYER && playerController.playerControlMode) {
            playerController.exitControlMode()
        }
        activeTab = tab
        val tabs = listOf(tabMessages, tabErrorLogs, tabHistory, tabPlayer)
        val contents = listOf(contentMessages, contentErrorLogs, contentHistory, contentPlayer)
        tabs.forEachIndexed { index, button ->
            val selected = Tab.entries[index] == tab
            button.isSelected = selected
            button.alpha = if (selected) 1f else 0.75f
        }
        contents.forEachIndexed { index, view ->
            view.visibility = if (Tab.entries[index] == tab) View.VISIBLE else View.GONE
        }
        if (tab == Tab.PLAYER) {
            loadChannelsIfNeeded()
        }
    }

    private fun wirePlayerButtons(
        chDown: MaterialButton,
        playPause: MaterialButton,
        chUp: MaterialButton,
        fullscreen: MaterialButton,
    ) {
        chDown.setOnClickListener { playerController.channelDown() }
        chUp.setOnClickListener { playerController.channelUp() }
        playPause.setOnClickListener { playerController.togglePlayPause() }
        fullscreen.setOnClickListener { playerController.openFullscreen() }
    }

    private fun updateControlModeUi(active: Boolean) {
        playerControlOverlay.visibility = if (active && !playerController.hasPlaybackError) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (active && !playerController.hasPlaybackError) {
            maybeShowControlHint()
            buttonOverlayChDown.requestFocus()
        } else if (!playerController.hasPlaybackError) {
            buttonPlayerChDown.requestFocus()
        }
    }

    private fun renderPlayerError(state: PlayerErrorState?) {
        playerErrorOverlay.bind(state)
        if (state != null) {
            playerControlOverlay.visibility = View.GONE
            playerErrorOverlay.requestInitialFocus()
        }
    }

    private fun maybeShowControlHint() {
        val prefs = activity.getSharedPreferences(PREFS_PLAYER_UX, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONTROL_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_CONTROL_HINT_SHOWN, true).apply()
        Toast.makeText(
            activity,
            R.string.bottom_panel_player_control_hint,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun isFocusInPlayerContent(): Boolean {
        val focused = activity.currentFocus ?: return false
        if (focused === contentPlayer) return true
        var current: View? = focused
        while (current != null) {
            if (current === contentPlayer) return true
            current = current.parent as? View
        }
        return false
    }

    private fun renderMessages(messages: List<GatewayMessage>) {
        textMessages.text = if (messages.isEmpty()) {
            activity.getString(R.string.bottom_panel_messages_empty)
        } else {
            messages.joinToString("\n") { it.formatLine() }
        }
        scrollMessages.post { scrollMessages.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderErrorLogs(lines: List<GatewayLogLine>) {
        textErrorLogs.text = if (lines.isEmpty()) {
            activity.getString(R.string.bottom_panel_logs_empty)
        } else {
            lines.joinToString("\n") { it.formatLine() }
        }
        scrollErrorLogs.post { scrollErrorLogs.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderHistory(entries: List<ChannelHistoryEntry>) {
        historyAdapter.submit(entries)
        textHistoryEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerHistory.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatChannelLabel(channel: TuneChannel): String =
        activity.getString(R.string.bottom_panel_player_channel, channel.number, channel.name)

    private fun wireTabFocus() {
        tabMessages.nextFocusRightId = tabErrorLogs.id
        tabErrorLogs.nextFocusLeftId = tabMessages.id
        tabErrorLogs.nextFocusRightId = tabHistory.id
        tabHistory.nextFocusLeftId = tabErrorLogs.id
        tabHistory.nextFocusRightId = tabPlayer.id
        tabPlayer.nextFocusLeftId = tabHistory.id

        tabMessages.nextFocusDownId = R.id.contentMessages
        tabErrorLogs.nextFocusDownId = R.id.scrollErrorLogs
        tabHistory.nextFocusDownId = R.id.recyclerHistory
        tabPlayer.nextFocusDownId = R.id.buttonPlayerChDown

        scrollMessages.nextFocusUpId = R.id.tabMessages
        scrollErrorLogs.nextFocusUpId = R.id.tabErrorLogs
        recyclerHistory.nextFocusUpId = R.id.tabHistory
    }

    private fun releaseFocusFromBrowseContent(): Boolean {
        if (!isFocusInBrowseContent()) return false
        activity.currentFocus?.clearFocus()
        activeTabButton().requestFocus()
        return true
    }

    private fun isFocusInBrowseContent(): Boolean {
        val focused = activity.currentFocus ?: return false
        return when (activeTab) {
            Tab.MESSAGES -> isDescendantOf(contentMessages, focused)
            Tab.ERROR_LOGS -> isDescendantOf(contentErrorLogs, focused)
            Tab.HISTORY -> isDescendantOf(contentHistory, focused)
            Tab.PLAYER -> false
        }
    }

    private fun isDescendantOf(container: View, focused: View): Boolean {
        if (focused === container) return true
        var current: View? = focused
        while (current != null) {
            if (current === container) return true
            current = current.parent as? View
        }
        return false
    }

    private fun activeTabButton(): MaterialButton = when (activeTab) {
        Tab.MESSAGES -> tabMessages
        Tab.ERROR_LOGS -> tabErrorLogs
        Tab.HISTORY -> tabHistory
        Tab.PLAYER -> tabPlayer
    }

    private enum class Tab {
        MESSAGES, ERROR_LOGS, HISTORY, PLAYER
    }

    private class HistoryAdapter(
        private val onTune: (ChannelHistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        private var entries: List<ChannelHistoryEntry> = emptyList()

        fun submit(list: List<ChannelHistoryEntry>) {
            entries = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_channel, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position], onTune)
        }

        override fun getItemCount(): Int = entries.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textLine: TextView = itemView.findViewById(R.id.textHistoryLine)
            private val textTime: TextView = itemView.findViewById(R.id.textHistoryTime)

            fun bind(entry: ChannelHistoryEntry, onTune: (ChannelHistoryEntry) -> Unit) {
                textLine.text = itemView.context.getString(
                    R.string.bottom_panel_history_line,
                    entry.number,
                    entry.name,
                )
                textTime.text = entry.formatTimestamp()
                itemView.setOnClickListener { onTune(entry) }
                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        onTune(entry)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFS_PLAYER_UX = "stepdaddy_player_ux"
        private const val KEY_CONTROL_HINT_SHOWN = "control_hint_shown"
    }
}
