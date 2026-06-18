package com.nova.stepdaddylivehd.gateway.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nova.stepdaddylivehd.gateway.BuildConfig
import com.nova.stepdaddylivehd.gateway.GatewayApp
import com.nova.stepdaddylivehd.gateway.GatewayStartHelper
import com.nova.stepdaddylivehd.gateway.PermissionHelper
import com.nova.stepdaddylivehd.gateway.R
import com.nova.stepdaddylivehd.gateway.ServerService
import com.nova.stepdaddylivehd.gateway.TiviMateLauncher
import com.nova.stepdaddylivehd.gateway.databinding.ActivityMainBinding
import com.nova.stepdaddylivehd.gateway.databinding.DialogQrCodeBinding
import com.nova.stepdaddylivehd.gateway.install.ApkInstallManager
import com.nova.stepdaddylivehd.gateway.install.InstallAppsCatalogRepository
import com.nova.stepdaddylivehd.gateway.model.HealthResponse
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
    private lateinit var environment: com.nova.stepdaddylivehd.gateway.GatewayEnvironment
    private lateinit var statusMonitor: GatewayStatusMonitor
    private lateinit var catalogRepository: InstallAppsCatalogRepository
    private lateinit var installManager: ApkInstallManager
    private var pollJob: Job? = null
    private var clockJob: Job? = null
    private var tivimateInstallJob: Job? = null
    private var restartJob: Job? = null
    private val tivimateInstallMutex = Mutex()
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
        requestRuntimePermissions()
        bindUrls()
        bindVersion()
        bindToggles()
        bindActions()
        updateStatus()
        updateEpgStatus()
        updateFooterMetrics(null)
        maybeAutoStartServer()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        updateStatus()
        updateEpgStatus()
        startStatusPolling()
        startClock()
    }

    override fun onPause() {
        pollJob?.cancel()
        clockJob?.cancel()
        isInForeground = false
        super.onPause()
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
        views.buttonCopyPlaylist.setOnClickListener { copyUrl(playlistUrl()) }
        views.buttonOpenPlaylist.setOnClickListener { openUrl(playlistUrl()) }
        views.buttonQrPlaylist.setOnClickListener { showQrDialog(playlistUrl()) }
        views.buttonCopyEpg.setOnClickListener { copyUrl(epgUrl()) }
        views.buttonOpenEpg.setOnClickListener { openUrl(epgUrl()) }
        views.buttonLaunchTivimate.setOnClickListener { launchTivimate() }
        views.buttonInstallTivimate.setOnClickListener { installTivimate() }
        views.buttonFooterScrollTop.setOnClickListener {
            views.scrollDashboard.smoothScrollTo(0, 0)
            views.buttonHeaderSettings.requestFocus()
        }
        views.buttonHeaderSettings.requestFocus()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
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

    private fun playlistUrl(): String = "${environment.loopbackBase()}/tivimate-playlist.m3u8"

    private fun epgUrl(): String = "${environment.loopbackBase()}/epg.xml"

    private fun healthUrl(): String = "${environment.loopbackBase()}/health"

    private fun startServer() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ServerService::class.java),
        )
        environment.serverRunning = true
        updateStatus()
        updateEpgStatus()
        Toast.makeText(this, R.string.toast_server_starting, Toast.LENGTH_SHORT).show()
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
    }

    private fun restartServer() {
        if (restartJob?.isActive == true) return
        restartJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, R.string.toast_server_restarting, Toast.LENGTH_SHORT).show()
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
        updateSummaryStatus(running, active, null)
        updateFooterOnline(running)
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
                epgManager.meta.state == "building" -> getString(R.string.status_epg_building)
                epgManager.epgReady() -> getString(R.string.status_epg_ready, epgManager.programmeCount())
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
                } else {
                    renderDashboard(null)
                }
                updateFooterMetrics(if (active) environment.lastServiceStartMs else null)
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun renderDashboard(live: GatewayLiveStatus?) {
        if (live == null || live.health == null) {
            views.textHealthStatus.text = getString(R.string.status_offline_upper)
            views.textHealthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            views.textHealthSubtitle.text = getString(R.string.dashboard_health_offline_detail)
            views.imageHealthBadge.setColorFilter(ContextCompat.getColor(this, R.color.status_neutral))
            views.textActivity.text = getString(R.string.dashboard_activity_idle)
            views.textErrors.visibility = View.GONE
            DashboardBarRenderer.renderProviders(this, views.containerProviderBars, null)
            views.textProvidersTotal.text = ""
            DashboardBarRenderer.renderCategories(this, views.containerCategoryBars, emptyList())
            updateSummaryStatus(ServerService.isServiceActive, ServerService.isServiceActive, null)
            return
        }

        val health = live.health
        val online = live.fetchError == null && health.ok && !health.starting
        views.textHealthStatus.text = when {
            live.fetchError != null -> getString(R.string.status_offline_upper)
            health.starting -> getString(R.string.dashboard_health_starting).uppercase(Locale.US)
            health.ok -> getString(R.string.status_online_upper)
            else -> getString(R.string.status_offline_upper)
        }
        val healthColor = when {
            live.fetchError != null || !health.ok -> R.color.status_error
            health.starting -> R.color.status_warn
            else -> R.color.status_ok
        }
        views.textHealthStatus.setTextColor(ContextCompat.getColor(this, healthColor))
        views.imageHealthBadge.setColorFilter(ContextCompat.getColor(this, healthColor))
        views.textHealthSubtitle.text = when {
            live.fetchError != null -> live.fetchError
            health.ok && !health.starting -> getString(R.string.dashboard_health_ok_detail)
            else -> getString(R.string.dashboard_health_starting)
        }
        views.textActivity.text = getString(
            R.string.dashboard_activity_line,
            live.activityLabel,
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
            live.fetchError?.let { add(it) }
        }
        if (errors.isEmpty()) {
            views.textErrors.visibility = View.GONE
        } else {
            views.textErrors.visibility = View.VISIBLE
            views.textErrors.text = errors.joinToString("\n")
        }

        updateSummaryStatus(online, true, health)
        updateFooterOnline(online)
    }

    private fun updateSummaryStatus(online: Boolean, active: Boolean, health: HealthResponse?) {
        views.statChannelsValue.text = health?.let { numberFormat.format(it.providers?.total ?: it.channels) } ?: "—"
        views.statProgramsValue.text = health?.let { numberFormat.format(it.epgProgrammeCount) } ?: "—"
        views.statStatusValue.text = when {
            online -> getString(R.string.status_online)
            active -> getString(R.string.status_starting)
            else -> getString(R.string.status_offline)
        }
        views.statStatusValue.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    online -> R.color.status_ok
                    active -> R.color.status_warn
                    else -> R.color.status_neutral
                },
            ),
        )
        views.statStatusIcon.setColorFilter(
            ContextCompat.getColor(
                this,
                when {
                    online -> R.color.status_ok
                    active -> R.color.status_warn
                    else -> R.color.status_neutral
                },
            ),
        )
        views.statSourcesValue.text = health?.let { numberFormat.format(countActiveSources(it)) } ?: "—"
    }

    private fun countActiveSources(health: HealthResponse): Int {
        var count = 1
        val supplement = health.supplement
        if (supplement != null) {
            if (supplement.sidecarEnabled) count++
            if (supplement.sportsEnabled) count++
            if (supplement.iptvOrgEnabled) count++
        } else if (health.supplementEnabled) {
            count++
        }
        return count
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
        views.textFooterUpdate.visibility = View.GONE
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

    private fun showQrDialog(url: String) {
        val dialogBinding = DialogQrCodeBinding.inflate(LayoutInflater.from(this))
        dialogBinding.textQrUrl.text = url
        val bitmap = QrCodeHelper.encode(url, QR_SIZE_PX)
        if (bitmap != null) {
            dialogBinding.imageQrCode.setImageBitmap(bitmap)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_qr_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val STATUS_POLL_MS = 3_000L
        private const val CLOCK_TICK_MS = 30_000L
        private const val RESTART_DELAY_MS = 1_500L
        private const val QR_SIZE_PX = 512

        @Volatile
        var isInForeground: Boolean = false
    }
}
