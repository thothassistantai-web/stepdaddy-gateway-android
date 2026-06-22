package com.thothassistant.stepdaddy.gateway.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.GatewayStartHelper
import com.thothassistant.stepdaddy.gateway.PermissionHelper
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.ServerService
import com.thothassistant.stepdaddy.gateway.TiviMateLauncher
import com.thothassistant.stepdaddy.gateway.databinding.ActivityMainBinding
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardBottomPanel
import com.thothassistant.stepdaddy.gateway.ui.dashboard.GatewayMessageBus
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppsCatalogRepository
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.update.AppUpdateCoordinator
import com.thothassistant.stepdaddy.gateway.update.AppUpdateInfo
import com.thothassistant.stepdaddy.gateway.network.GatewayPeerScanner
import com.thothassistant.stepdaddy.gateway.network.GatewayUrlBuilder
import com.thothassistant.stepdaddy.gateway.network.LanAddressResolver
import com.thothassistant.stepdaddy.gateway.network.NetworkAccessMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var views: MainDashboardViews
    private lateinit var environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment
    private lateinit var statusMonitor: GatewayStatusMonitor
    private lateinit var catalogRepository: InstallAppsCatalogRepository
    private lateinit var installManager: ApkInstallManager
    private lateinit var updateCoordinator: AppUpdateCoordinator
    private var pollJob: Job? = null
    private var clockJob: Job? = null
    private var tivimateInstallJob: Job? = null
    private var restartJob: Job? = null
    private var peerScanJob: Job? = null
    private val mainUpdateListener: (AppUpdateInfo?) -> Unit = { onUpdateAvailability(it) }
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var lastGatewayOnline = false
    private val tivimateInstallMutex = Mutex()
    private lateinit var bottomPanel: DashboardBottomPanel
    private lateinit var statCards: DashboardStatCards
    private var lastGoodHealth: HealthResponse? = null
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.install_apps_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        views = MainDashboardViews(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        statusMonitor = GatewayStatusMonitor { healthUrl() }
        catalogRepository = InstallAppsCatalogRepository(this)
        installManager = ApkInstallManager(this)
        updateCoordinator = (application as GatewayApp).appUpdateCoordinator
        updateCoordinator.setPrimaryHost(this)
        updateCoordinator.addAvailabilityListener(mainUpdateListener)
        requestRuntimePermissions()
        bindUrls()
        bindVersion()
        bindNetworkMode()
        bindToggles()
        bindActions()
        statCards = DashboardStatCards(this, binding.root)
        statCards.wireClicks()
        bottomPanel = DashboardBottomPanel(this, binding.root, environment, lifecycleScope)
        bottomPanel.attach()
        GatewayMessageBus.post("Dashboard opened")
        scrollDashboardToTop()
        hydrateDashboardFromCache()
        updateStatus()
        updateEpgStatus()
        updateFooterMetrics(null)
        maybeAutoStartServer()
        updateCoordinator.scheduleStartupFlow(this)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (::updateCoordinator.isInitialized) {
            updateCoordinator.setPrimaryHost(this)
            updateCoordinator.flushPendingPrompts(this)
        }
        bottomPanel.onResume()
        hydrateDashboardFromCache()
        updateStatus()
        bindNetworkMode()
        bindUrls()
        updateEpgStatus()
        startStatusPolling()
        startClock()
        maybeScanLanPeers()
        scrollDashboardToTop()
    }

    private fun scrollDashboardToTop() {
        val scroll = views.scrollDashboard
        scroll.post {
            scroll.scrollTo(0, 0)
            scroll.post {
                scroll.fullScroll(View.FOCUS_UP)
                scroll.scrollTo(0, 0)
                views.buttonHeaderSettings.requestFocus()
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        clockJob?.cancel()
        peerScanJob?.cancel()
        bottomPanel.onPause()
        isInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::updateCoordinator.isInitialized) {
            updateCoordinator.removeAvailabilityListener(mainUpdateListener)
            updateCoordinator.setPrimaryHost(null)
        }
        if (::bottomPanel.isInitialized) {
            bottomPanel.onDestroy()
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::bottomPanel.isInitialized && bottomPanel.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun maybeAutoStartServer() {
        if (!environment.autoStartOnLaunch) return
        if (ServerService.isServiceActive) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, ServerService::class.java),
        )
        environment.serverRunning = true
        updateStatus()
    }

    private fun requestRuntimePermissions() {
        PermissionHelper.requestNotificationPermission(this)
        PermissionHelper.requestOverlayPermission(this)
        if (!PermissionHelper.isBatteryOptimizationIgnored(this)) {
            PermissionHelper.requestBatteryOptimizationExemption(this)
        }
        PermissionHelper.requestExactAlarmPermission(this)
    }

    private fun bindVersion() {
        val built = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME))
        views.textVersion.text = getString(
            R.string.label_version_line,
            BuildConfig.VERSION_NAME,
            BuildConfig.GIT_HASH,
            built,
        )
    }

    private fun bindNetworkMode() {
        val label = when (environment.networkAccessMode) {
            NetworkAccessMode.DEFAULT -> getString(R.string.network_mode_default)
            NetworkAccessMode.LOCAL -> getString(R.string.network_mode_local)
            NetworkAccessMode.REMOTE -> getString(R.string.network_mode_remote)
        }
        views.textNetworkMode.text = getString(R.string.label_network_mode, label)
        views.textTitle.text = environment.displayGatewayName()
    }

    private fun bindUrls() {
        views.textPlaylistUrl.text = playlistUrl()
        views.textEpgUrl.text = epgUrl()
        views.textPort.text = getString(R.string.label_port_value, environment.port)
    }

    private fun bindToggles() {
        views.switchAutoStart.setOnCheckedChangeListener(null)
        views.switchAutoStart.isChecked = environment.autoStartOnLaunch
        views.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            environment.autoStartOnLaunch = checked
        }

        views.switchLaunchTivimate.setOnCheckedChangeListener(null)
        views.switchLaunchTivimate.isChecked = environment.launchTivimateOnReady
        views.switchLaunchTivimate.setOnCheckedChangeListener { _, checked ->
            environment.launchTivimateOnReady = checked
        }

        views.switchBoot.setOnCheckedChangeListener(null)
        views.switchBoot.isChecked = environment.startOnBoot
        views.switchBoot.setOnCheckedChangeListener { _, checked ->
            environment.startOnBoot = checked
            if (checked) {
                GatewayStartHelper.schedulePeriodicEnsureAlive(this)
            } else {
                GatewayStartHelper.cancelPeriodicEnsureAlive(this)
                GatewayStartHelper.cancelBootFallbacks(this)
            }
        }

        views.switchTivimateWatch.setOnCheckedChangeListener(null)
        views.switchTivimateWatch.isChecked = environment.tivimateWatchEnabled
        views.switchTivimateWatch.setOnCheckedChangeListener { _, checked ->
            environment.tivimateWatchEnabled = checked
        }
    }

    private fun bindActions() {
        views.buttonToggleServer.setOnClickListener {
            if (ServerService.isServiceActive) {
                stopServer()
            } else {
                startServer()
            }
        }
        views.buttonRestart.setOnClickListener { restartServer() }
        views.buttonSettings.setOnClickListener { openSettings() }
        views.buttonInstallApps.setOnClickListener {
            startActivity(Intent(this, InstallAppsActivity::class.java))
        }
        views.buttonHeaderSettings.setOnClickListener { openSettings() }
        views.buttonHeaderUpdate.setOnClickListener { checkForUpdates(manual = true) }
        views.buttonCopyPlaylist.setOnClickListener { copyUrl(playlistUrl()) }
        views.buttonOpenPlaylist.setOnClickListener { openUrl(playlistUrl()) }
        views.buttonQrPlaylist.setOnClickListener { QrCodeDialogController(this, environment).show() }
        views.buttonCopyEpg.setOnClickListener { copyUrl(epgUrl()) }
        views.buttonOpenEpg.setOnClickListener { openUrl(epgUrl()) }
        views.buttonLaunchTivimate.setOnClickListener { launchTivimate() }
        views.buttonInstallTivimate.setOnClickListener { installTivimate() }
        views.buttonFooterScrollTop.setOnClickListener {
            scrollDashboardToTop()
        }
    }

    private fun hydrateDashboardFromCache() {
        if (!ServerService.isServiceActive) return
        val cached = GatewayStatusMonitor.lastCachedStatus ?: return
        if (cached.health != null) {
            lastGoodHealth = cached.health
            renderDashboard(cached)
        }
    }

    private fun healthForDisplay(live: GatewayLiveStatus?): HealthResponse? {
        live?.health?.let { return it }
        if (live?.fetchError != null) {
            lastGoodHealth?.let { return it }
            GatewayStatusMonitor.lastCachedStatus?.health?.let { return it }
        }
        return null
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun checkForUpdates(manual: Boolean) {
        updateCoordinator.checkForUpdate(this, manual)
    }

    private fun onUpdateAvailability(info: AppUpdateInfo?) {
        pendingUpdateInfo = info
        updateFooterUpdateVisibility(info)
    }

    private fun updateFooterUpdateVisibility(info: AppUpdateInfo?) {
        if (info == null) {
            views.textFooterUpdate.visibility = View.GONE
            return
        }
        views.textFooterUpdate.text = getString(
            R.string.footer_update_available,
            info.manifest.versionName,
        )
        views.textFooterUpdate.visibility = View.VISIBLE
    }

    private fun launchTivimate() {
        if (TiviMateLauncher.launch(this)) return
        if (!TiviMateLauncher.isInstalled(this)) {
            Toast.makeText(this, R.string.toast_tivimate_not_installed, Toast.LENGTH_LONG).show()
            installTivimate()
            return
        }
        Toast.makeText(this, R.string.toast_tivimate_launch_failed, Toast.LENGTH_SHORT).show()
    }

    private fun installTivimate() {
        if (!ensureInstallAllowed()) return
        if (tivimateInstallJob?.isActive == true) return
        tivimateInstallJob = lifecycleScope.launch {
            setTivimateInstallBusy(true)
            runCatching {
                tivimateInstallMutex.withLock {
                    val catalog = catalogRepository.loadCatalog()
                    val entry = catalogRepository.findBestTiviMateEntry(catalog)
                        ?: error(getString(R.string.toast_tivimate_catalog_missing))
                    Toast.makeText(
                        this@MainActivity,
                        R.string.toast_tivimate_installing,
                        Toast.LENGTH_SHORT,
                    ).show()
                    GatewayMessageBus.postInstallProgress("TiviMate", "downloading")
                    val apkFile = installManager.downloadApk(entry) { }
                    if (!installManager.launchInstall(apkFile)) {
                        error(getString(R.string.install_apps_launch_failed))
                    }
                    entry
                }
            }.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_tivimate_install_ready,
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { exc ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_tivimate_install_failed, exc.message ?: "error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            setTivimateInstallBusy(false)
        }
    }

    private fun ensureInstallAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !installManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(this, R.string.install_apps_unknown_sources_hint, Toast.LENGTH_LONG).show()
            installPermissionLauncher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
            installManager.openInstallPermissionSettings()
            return false
        }
        return true
    }

    private fun setTivimateInstallBusy(busy: Boolean) {
        views.buttonInstallTivimate.isEnabled = !busy
        views.buttonLaunchTivimate.isEnabled = !busy
    }

    private fun playlistUrl(): String = GatewayUrlBuilder.playlistUrl(environment)

    private fun epgUrl(): String = GatewayUrlBuilder.epgUrl(environment)

    private fun healthUrl(): String = GatewayUrlBuilder.healthUrl(environment)

    private fun maybeScanLanPeers() {
        if (environment.networkAccessMode == NetworkAccessMode.DEFAULT) {
            views.textPeerBanner.visibility = View.GONE
            return
        }
        peerScanJob?.cancel()
        peerScanJob = lifecycleScope.launch {
            val ownIp = LanAddressResolver.lanIpv4()
            val peers = GatewayPeerScanner.scan(ownIp, environment.port)
            if (peers.isEmpty()) {
                views.textPeerBanner.visibility = View.GONE
            } else {
                views.textPeerBanner.visibility = View.VISIBLE
                views.textPeerBanner.text = getString(
                    R.string.dashboard_peer_banner,
                    peers.joinToString(", ") { it.ip },
                )
            }
        }
    }

    private fun startServer() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ServerService::class.java),
        )
        environment.serverRunning = true
        updateStatus()
        updateEpgStatus()
        Toast.makeText(this, R.string.toast_server_starting, Toast.LENGTH_SHORT).show()
        GatewayMessageBus.post("Starting gateway server")
        updateCoordinator.deferPrompts()
    }

    private fun stopServer() {
        startService(
            Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP
            },
        )
        environment.serverRunning = false
        updateStatus()
        updateEpgStatus()
        renderDashboard(null)
        Toast.makeText(this, R.string.toast_server_stopped, Toast.LENGTH_SHORT).show()
        GatewayMessageBus.post("Gateway server stopped", "WARN")
    }

    private fun restartServer() {
        if (restartJob?.isActive == true) return
        restartJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, R.string.toast_server_restarting, Toast.LENGTH_SHORT).show()
            GatewayMessageBus.post("Restarting gateway server")
            if (ServerService.isServiceActive) {
                stopServer()
                delay(RESTART_DELAY_MS)
            }
            startServer()
        }
    }

    private fun updateStatus() {
        val active = ServerService.isServiceActive
        val running = active && environment.serverRunning
        environment.serverRunning = running
        views.textStatus.text = when {
            running -> getString(R.string.status_running, environment.loopbackBase())
            active -> getString(R.string.status_starting)
            else -> getString(R.string.status_stopped)
        }
        updateServerToggleButton(active)
        views.buttonRestart.isEnabled = active
        val cached = GatewayStatusMonitor.lastCachedStatus?.takeIf { it.health != null }
        val health = cached?.health ?: lastGoodHealth
        if (health != null && active) {
            val online = cached?.let { live ->
                live.fetchError == null && live.health?.let { h ->
                    h.ok && (!h.starting || h.channels > 0 || (h.providers?.total ?: 0) > 0)
                } == true
            } ?: lastGatewayOnline
            statCards.bind(health, online, true)
        } else {
            statCards.bind(null, false, active)
        }
        if (!active) {
            updateFooterOnline(false)
        }
    }

    private fun updateServerToggleButton(active: Boolean) {
        val button = views.buttonToggleServer
        if (active) {
            button.text = getString(R.string.action_stop)
            button.setIconResource(R.drawable.ic_stop)
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_stop)
        } else {
            button.text = getString(R.string.action_start)
            button.setIconResource(R.drawable.ic_play)
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_start)
        }
        button.isEnabled = true
    }

    private fun updateEpgStatus() {
        lifecycleScope.launch {
            val app = application as GatewayApp
            runCatching { app.awaitComponents() }
            val epgManager = app.epgManager
            views.textEpgStatus.text = when {
                !epgManager.gatewayEpgEnabled() -> getString(R.string.status_epg_external)
                epgManager.isBuilding() -> getString(R.string.status_epg_building)
                epgManager.epgReady() -> getString(R.string.status_epg_ready, epgManager.programmeCount())
                epgManager.meta.state == "error" -> getString(
                    R.string.status_epg_error,
                    epgManager.meta.lastError ?: "unknown",
                )
                epgManager.needsBuild() -> getString(R.string.status_epg_building)
                else -> getString(R.string.status_epg_pending)
            }
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = lifecycleScope.launch {
            while (isActive) {
                views.textClock.text = clockFormat.format(Date())
                delay(CLOCK_TICK_MS)
            }
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            var lastActive = ServerService.isServiceActive
            while (isActive) {
                val active = ServerService.isServiceActive
                if (active != lastActive) {
                    updateStatus()
                    lastActive = active
                }
                if (active) {
                    val live = statusMonitor.fetch()
                    renderDashboard(live)
                    updateEpgStatus()
                } else {
                    renderDashboard(null)
                }
                updateFooterMetrics(if (active) environment.lastServiceStartMs else null)
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun renderDashboard(live: GatewayLiveStatus?) {
        val health = healthForDisplay(live)
        if (health == null) {
            views.textHealthStatus.text = getString(R.string.status_offline_upper)
            views.textHealthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            views.textHealthSubtitle.text = live?.fetchError ?: getString(R.string.dashboard_health_offline_detail)
            views.imageHealthBadge.setColorFilter(ContextCompat.getColor(this, R.color.status_neutral))
            views.textActivity.text = getString(R.string.dashboard_activity_idle)
            views.textErrors.visibility = if (live?.fetchError != null) View.VISIBLE else View.GONE
            views.textErrors.text = live?.fetchError.orEmpty()
            DashboardBarRenderer.renderProviders(this, views.containerProviderBars, null)
            views.textProvidersTotal.text = ""
            DashboardBarRenderer.renderCategories(this, views.containerCategoryBars, emptyList())
            statCards.bind(lastGoodHealth, false, ServerService.isServiceActive)
            return
        }

        if (live?.health != null) {
            lastGoodHealth = live.health
        }
        val fetchError = live?.fetchError
        val online = health.ok &&
            (!health.starting || health.channels > 0 || (health.providers?.total ?: 0) > 0)
        if (online && !lastGatewayOnline) {
            updateCoordinator.deferPrompts()
        }
        lastGatewayOnline = online
        views.textHealthStatus.text = when {
            fetchError != null -> getString(R.string.status_offline_upper)
            health.channels == 0 && (health.providers?.total ?: 0) == 0 && health.ok ->
                getString(R.string.status_loading_short).uppercase(Locale.US)
            health.starting -> getString(R.string.status_loading_short).uppercase(Locale.US)
            health.ok -> getString(R.string.status_online_upper)
            else -> getString(R.string.status_offline_upper)
        }
        val healthColor = when {
            fetchError != null || !health.ok -> R.color.status_error
            health.starting -> R.color.status_warn
            else -> R.color.status_ok
        }
        views.textHealthStatus.setTextColor(ContextCompat.getColor(this, healthColor))
        views.imageHealthBadge.setColorFilter(ContextCompat.getColor(this, healthColor))
        views.textHealthSubtitle.text = when {
            fetchError != null -> fetchError
            health.channels == 0 && (health.providers?.total ?: 0) == 0 && health.ok ->
                getString(R.string.dashboard_health_starting)
            health.ok && !health.starting -> getString(R.string.dashboard_health_ok_detail)
            else -> getString(R.string.dashboard_health_starting)
        }
        views.textActivity.text = getString(
            R.string.dashboard_activity_line,
            live?.activityLabel ?: GatewayLiveStatus().activityLabel,
            health.upstreamBaseUrl,
        )

        DashboardBarRenderer.renderProviders(this, views.containerProviderBars, health.providers)
        val total = health.providers?.total ?: health.channels
        views.textProvidersTotal.text = getString(
            R.string.dashboard_providers_total,
            numberFormat.format(total),
        )
        DashboardBarRenderer.renderCategories(this, views.containerCategoryBars, health.topCategories)

        val healing = health.healing
        val errors = buildList {
            if (healing?.breakerOpen == true) {
                add(getString(R.string.dashboard_error_breaker, healing.breakerRemainingMs))
            }
            if (healing?.outageMode == true) add(getString(R.string.dashboard_error_outage))
            if (healing?.streamFailures ?: 0 > 0) {
                add(getString(R.string.dashboard_error_streams, healing?.streamFailures ?: 0))
            }
            if ((health.supplement?.iptvOrgPlaylistsFailed ?: 0) > 0) {
                add(
                    getString(
                        R.string.dashboard_error_iptv_playlists,
                        health.supplement?.iptvOrgPlaylistsFailed ?: 0,
                    ),
                )
            }
            fetchError?.let { add(it) }
        }
        if (errors.isEmpty()) {
            views.textErrors.visibility = View.GONE
        } else {
            views.textErrors.visibility = View.VISIBLE
            views.textErrors.text = errors.joinToString("\n")
        }

        statCards.bind(health, online, true)
        updateFooterOnline(online)
        pendingUpdateInfo = updateCoordinator.currentUpdate()
        updateFooterUpdateVisibility(pendingUpdateInfo)
    }

    private fun updateFooterOnline(online: Boolean) {
        views.viewFooterStatusDot.setBackgroundResource(
            if (online) R.drawable.bg_stat_indicator_online else R.drawable.bg_stat_indicator_offline,
        )
        views.textFooterStatus.text = if (online) {
            getString(R.string.footer_status_online)
        } else {
            getString(R.string.footer_status_offline)
        }
    }

    private fun updateFooterMetrics(serviceStartMs: Long?) {
        views.textFooterUptime.text = if (serviceStartMs != null && serviceStartMs > 0L) {
            getString(R.string.footer_uptime, formatUptime(System.currentTimeMillis() - serviceStartMs))
        } else {
            getString(R.string.footer_uptime_placeholder)
        }
        views.textFooterClients.text = getString(R.string.footer_clients, "—")
        val memoryMb = readProcessMemoryMb()
        views.textFooterMemory.text = if (memoryMb > 0) {
            getString(R.string.footer_memory, memoryMb)
        } else {
            getString(R.string.footer_memory_placeholder)
        }
        updateFooterUpdateVisibility(pendingUpdateInfo)
    }

    private fun readProcessMemoryMb(): Int {
        val info = ActivityManager.MemoryInfo()
        val manager = getSystemService(ActivityManager::class.java) ?: return 0
        manager.getMemoryInfo(info)
        val pid = android.os.Process.myPid()
        val processes = manager.getProcessMemoryInfo(intArrayOf(pid))
        val totalPssKb = processes.firstOrNull()?.totalPss ?: return 0
        return (totalPssKb / 1024.0).roundToInt()
    }

    private fun formatUptime(elapsedMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stepdaddy_url", url))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.toast_open_url_failed, Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        private const val STATUS_POLL_MS = 3_000L
        private const val CLOCK_TICK_MS = 30_000L
        private const val RESTART_DELAY_MS = 1_500L

        @Volatile
        var isInForeground: Boolean = false
    }
}
