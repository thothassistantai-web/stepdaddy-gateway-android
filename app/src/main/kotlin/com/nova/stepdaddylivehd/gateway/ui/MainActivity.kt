package com.nova.stepdaddylivehd.gateway.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nova.stepdaddylivehd.gateway.install.ApkInstallManager
import com.nova.stepdaddylivehd.gateway.install.InstallAppsCatalogRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var environment: com.nova.stepdaddylivehd.gateway.GatewayEnvironment
    private lateinit var statusMonitor: GatewayStatusMonitor
    private lateinit var catalogRepository: InstallAppsCatalogRepository
    private lateinit var installManager: ApkInstallManager
    private var pollJob: Job? = null
    private var tivimateInstallJob: Job? = null
    private val tivimateInstallMutex = Mutex()

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
        maybeAutoStartServer()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        updateStatus()
        updateEpgStatus()
        startStatusPolling()
    }

    override fun onPause() {
        pollJob?.cancel()
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
        binding.textVersion.text = getString(
            R.string.label_version_line,
            BuildConfig.VERSION_NAME,
            BuildConfig.GIT_HASH,
            built,
        )
    }

    private fun bindUrls() {
        binding.textPlaylistUrl.text = playlistUrl()
        binding.textEpgUrl.text = epgUrl()
        binding.textPort.text = getString(R.string.label_port_value, environment.port)
    }

    private fun bindToggles() {
        binding.switchAutoStart.setOnCheckedChangeListener(null)
        binding.switchAutoStart.isChecked = environment.autoStartOnLaunch
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            environment.autoStartOnLaunch = checked
        }

        binding.switchLaunchTivimate.setOnCheckedChangeListener(null)
        binding.switchLaunchTivimate.isChecked = environment.launchTivimateOnReady
        binding.switchLaunchTivimate.setOnCheckedChangeListener { _, checked ->
            environment.launchTivimateOnReady = checked
        }

        binding.switchBoot.setOnCheckedChangeListener(null)
        binding.switchBoot.isChecked = environment.startOnBoot
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            environment.startOnBoot = checked
            if (checked) {
                GatewayStartHelper.schedulePeriodicEnsureAlive(this)
            } else {
                GatewayStartHelper.cancelPeriodicEnsureAlive(this)
                GatewayStartHelper.cancelBootFallbacks(this)
            }
        }

        binding.switchTivimateWatch.setOnCheckedChangeListener(null)
        binding.switchTivimateWatch.isChecked = environment.tivimateWatchEnabled
        binding.switchTivimateWatch.setOnCheckedChangeListener { _, checked ->
            environment.tivimateWatchEnabled = checked
        }
    }

    private fun bindActions() {
        binding.buttonStart.setOnClickListener { startServer() }
        binding.buttonStop.setOnClickListener { stopServer() }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.buttonInstallApps.setOnClickListener {
            startActivity(Intent(this, InstallAppsActivity::class.java))
        }
        binding.buttonCopyPlaylist.setOnClickListener { copyUrl(playlistUrl()) }
        binding.buttonCopyEpg.setOnClickListener { copyUrl(epgUrl()) }
        binding.buttonLaunchTivimate.setOnClickListener { launchTivimate() }
        binding.buttonInstallTivimate.setOnClickListener { installTivimate() }
        binding.buttonStart.requestFocus()
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
        binding.buttonInstallTivimate.isEnabled = !busy
        binding.buttonLaunchTivimate.isEnabled = !busy
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

    private fun updateStatus() {
        val active = ServerService.isServiceActive
        val running = active && environment.serverRunning
        environment.serverRunning = running
        binding.textStatus.text = when {
            running -> getString(R.string.status_running, environment.loopbackBase())
            active -> getString(R.string.status_starting)
            else -> getString(R.string.status_stopped)
        }
        binding.buttonStart.isEnabled = !active
        binding.buttonStop.isEnabled = active
    }

    private fun updateEpgStatus() {
        lifecycleScope.launch {
            val app = application as GatewayApp
            runCatching { app.awaitComponents() }
            val epgManager = app.epgManager
            binding.textEpgStatus.text = when {
                epgManager.meta.state == "building" -> getString(R.string.status_epg_building)
                epgManager.epgReady() -> getString(R.string.status_epg_ready, epgManager.programmeCount())
                else -> getString(R.string.status_epg_pending)
            }
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (ServerService.isServiceActive) {
                    val live = statusMonitor.fetch()
                    renderDashboard(live)
                } else {
                    renderDashboard(null)
                }
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun renderDashboard(live: GatewayLiveStatus?) {
        if (live == null || live.health == null) {
            binding.textHealthStatus.text = getString(R.string.dashboard_health_offline)
            binding.textHealthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            binding.textActivity.text = getString(R.string.dashboard_activity_idle)
            binding.textProviders.text = getString(R.string.dashboard_providers_empty)
            binding.textErrors.visibility = View.GONE
            binding.textCategories.text = ""
            return
        }

        val health = live.health
        binding.textHealthStatus.text = when {
            live.fetchError != null -> getString(R.string.dashboard_health_error, live.fetchError)
            health.starting -> getString(R.string.dashboard_health_starting)
            health.ok -> getString(R.string.dashboard_health_ok, health.channels)
            else -> getString(R.string.dashboard_health_error, "unknown")
        }
        val healthColor = when {
            live.fetchError != null || !health.ok -> R.color.status_error
            health.starting -> R.color.status_warn
            else -> R.color.status_ok
        }
        binding.textHealthStatus.setTextColor(ContextCompat.getColor(this, healthColor))
        binding.textActivity.text = getString(
            R.string.dashboard_activity_line,
            live.activityLabel,
            health.upstreamBaseUrl,
        )

        val providers = health.providers
        binding.textProviders.text = if (providers != null) {
            getString(
                R.string.dashboard_providers,
                providers.daddylive,
                providers.moveOnJoy,
                providers.iptvOrg,
                providers.sports,
                providers.adult,
                providers.total,
            )
        } else {
            getString(R.string.dashboard_providers_empty)
        }

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
            binding.textErrors.visibility = View.GONE
        } else {
            binding.textErrors.visibility = View.VISIBLE
            binding.textErrors.text = errors.joinToString("\n")
        }

        binding.textCategories.text = health.topCategories.joinToString("\n") { row ->
            getString(R.string.dashboard_category_row, row.groupTitle, row.count)
        }
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stepdaddy_url", url))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATUS_POLL_MS = 3_000L

        @Volatile
        var isInForeground: Boolean = false
    }
}
