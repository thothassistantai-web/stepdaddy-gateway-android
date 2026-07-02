package com.thothassistant.stepdaddy.gateway.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.databinding.ActivityPlayerFullscreenBinding
import com.thothassistant.stepdaddy.gateway.ui.dashboard.ChannelHistoryStore
import com.thothassistant.stepdaddy.gateway.ui.dashboard.ChannelListProvider
import com.thothassistant.stepdaddy.gateway.ui.dashboard.TuneChannel
import com.thothassistant.stepdaddy.gateway.ui.player.FullscreenPlayerController
import com.thothassistant.stepdaddy.gateway.ui.player.PlayerDeviceProfile
import com.thothassistant.stepdaddy.gateway.ui.player.PlayerErrorOverlay
import com.thothassistant.stepdaddy.gateway.ui.player.PlayerInputRouter
import kotlinx.coroutines.launch

class PlayerFullscreenActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerFullscreenBinding
    private lateinit var controller: FullscreenPlayerController
    private lateinit var inputRouter: PlayerInputRouter
    private lateinit var errorOverlay: PlayerErrorOverlay
    private val historyStore by lazy { ChannelHistoryStore(this) }
    private val isTvDevice by lazy { PlayerDeviceProfile.isTvDevice(this) }

    private var startChannelId: String = ""
    private var startChannelName: String = ""
    private var startChannelNumber: Int = 0
    private var startChannelGroup: String = ""
    private var channelsLoaded = false

    private val overlayButtons: List<View> by lazy {
        listOf(
            binding.buttonFullscreenChDown,
            binding.buttonFullscreenPlayPause,
            binding.buttonFullscreenChUp,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (isTvDevice) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        binding = ActivityPlayerFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        readIntentExtras()
        enterImmersive()
        setupErrorOverlay()
        setupController()
        setupInputRouter()
        setupTouch()
        wireOverlayButtons()
        loadChannelsAndTune()
        binding.playerViewFullscreen.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        controller.attach()
    }

    override fun onStop() {
        controller.release()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputRouter.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun readIntentExtras() {
        startChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        startChannelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        startChannelNumber = intent.getIntExtra(EXTRA_CHANNEL_NUMBER, 0)
        startChannelGroup = intent.getStringExtra(EXTRA_CHANNEL_GROUP).orEmpty()
    }

    private fun setupErrorOverlay() {
        errorOverlay = PlayerErrorOverlay(
            root = binding.root,
            onRetry = { controller.retryCurrentChannel() },
            onNextChannel = { controller.nextChannelAfterError() },
        )
    }

    private fun setupController() {
        val environment = (application as GatewayApp).gatewayEnvironment
        controller = FullscreenPlayerController(
            context = this,
            environment = environment,
            scope = lifecycleScope,
            playerView = binding.playerViewFullscreen,
            historyStore = historyStore,
            onUiChanged = { state -> renderUi(state) },
        )
    }

    private fun setupInputRouter() {
        inputRouter = PlayerInputRouter(
            isTvDevice = isTvDevice,
            callbacks = object : PlayerInputRouter.Callbacks {
                override fun onExitFullscreen() = finish()

                override fun onChannelUp() = controller.channelUp()

                override fun onChannelDown() = controller.channelDown()

                override fun onToggleOverlay() = controller.toggleOverlay()

                override fun onShowOverlay() = controller.showOverlay()

                override fun onTogglePlayPause() = controller.togglePlayPause()

                override fun onToggleInfoBar() = controller.toggleInfoBar()

                override fun isOverlayVisible(): Boolean = controller.isOverlayVisible()

                override fun isOverlayButtonFocused(): Boolean {
                    val focused = currentFocus ?: return false
                    if (errorOverlay.isErrorButtonFocused(focused)) return true
                    return overlayButtons.any { it === focused }
                }

                override fun onActivateFocusedButton(): Boolean {
                    val focused = currentFocus ?: return false
                    if (errorOverlay.isErrorButtonFocused(focused)) {
                        focused.performClick()
                        return true
                    }
                    if (focused in overlayButtons) {
                        focused.performClick()
                        return true
                    }
                    return false
                }
            },
        )
    }

    private fun setupTouch() {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    controller.toggleOverlay()
                    return true
                }
            },
        )
        binding.playerViewFullscreen.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun wireOverlayButtons() {
        binding.buttonFullscreenChDown.setOnClickListener { controller.channelDown() }
        binding.buttonFullscreenChUp.setOnClickListener { controller.channelUp() }
        binding.buttonFullscreenPlayPause.setOnClickListener { controller.togglePlayPause() }

        binding.buttonFullscreenChDown.nextFocusRightId = binding.buttonFullscreenPlayPause.id
        binding.buttonFullscreenPlayPause.nextFocusLeftId = binding.buttonFullscreenChDown.id
        binding.buttonFullscreenPlayPause.nextFocusRightId = binding.buttonFullscreenChUp.id
        binding.buttonFullscreenChUp.nextFocusLeftId = binding.buttonFullscreenPlayPause.id
    }

    private fun loadChannelsAndTune() {
        val environment = (application as GatewayApp).gatewayEnvironment
        val initial = TuneChannel(
            id = startChannelId,
            name = startChannelName,
            number = startChannelNumber,
            groupTitle = startChannelGroup,
        )
        lifecycleScope.launch {
            if (channelsLoaded) return@launch
            val channels = ChannelListProvider.loadSorted(environment)
            val list = when {
                channels.isNotEmpty() -> channels
                initial.id.isNotEmpty() -> listOf(initial)
                else -> emptyList()
            }
            if (list.isEmpty()) return@launch
            channelsLoaded = true
            controller.setChannels(list)
            val target = when {
                initial.id.isNotEmpty() -> initial
                historyStore.lastChannel() != null -> historyStore.lastChannel()!!
                else -> list.first()
            }
            controller.tuneTo(target)
        }
    }

    private fun renderUi(state: FullscreenPlayerController.UiState) {
        val channel = state.channel
        binding.textFullscreenChannel.text = formatChannelLine(channel)
        binding.textFullscreenGroup.text = channel?.groupTitle.orEmpty()
        binding.textFullscreenGroup.visibility =
            if (channel?.groupTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.fullscreenInfoBar.visibility =
            if (state.infoBarVisible) View.VISIBLE else View.GONE
        binding.fullscreenControlOverlay.visibility =
            if (state.overlayVisible && state.error == null) View.VISIBLE else View.GONE
        errorOverlay.bind(state.error)
        if (state.error != null) {
            errorOverlay.requestInitialFocus()
        } else if (state.overlayVisible) {
            binding.buttonFullscreenChDown.requestFocus()
        } else if (state.infoBarVisible) {
            binding.playerViewFullscreen.requestFocus()
        }
    }

    private fun formatChannelLine(channel: TuneChannel?): String {
        if (channel == null) return ""
        return if (channel.number > 0) {
            "${channel.number} · ${channel.name}"
        } else {
            channel.name.ifEmpty { channel.id }
        }
    }

    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { insetsController ->
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_NUMBER = "channel_number"
        const val EXTRA_CHANNEL_GROUP = "channel_group"

        fun intentExtras(channel: TuneChannel): Bundle =
            Bundle().apply {
                putString(EXTRA_CHANNEL_ID, channel.id)
                putString(EXTRA_CHANNEL_NAME, channel.name)
                putInt(EXTRA_CHANNEL_NUMBER, channel.number)
                putString(EXTRA_CHANNEL_GROUP, channel.groupTitle)
            }
    }
}
