package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.TiviMateController
import com.thothassistant.stepdaddy.gateway.TiviMateInstalledVariant
import com.thothassistant.stepdaddy.gateway.TiviMatePlaylistStateHelper
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.update.TiviMateUpdateCheckResult
import com.thothassistant.stepdaddy.gateway.update.TiviMateUpdateCoordinator
import com.thothassistant.stepdaddy.gateway.update.AppUpdateRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutActivity : AppCompatActivity() {
    private lateinit var tiviMateUpdateCoordinator: TiviMateUpdateCoordinator
    private lateinit var installManager: ApkInstallManager

    private lateinit var textGatewayBuildInfo: TextView
    private lateinit var textTiviMateInstalled: TextView
    private lateinit var textTiviMateLatest: TextView
    private lateinit var textTiviMateStatus: TextView
    private lateinit var textTiviMateUpdateBadge: TextView
    private lateinit var buttonCheckTiviMateUpdate: MaterialButton
    private lateinit var buttonUpdateTiviMateNow: MaterialButton
    private lateinit var buttonOpenReleases: MaterialButton
    private lateinit var textTiviMatePlaylistState: TextView

    private val updateListener: (TiviMateUpdateCheckResult?) -> Unit = { result ->
        renderTiviMateState(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        tiviMateUpdateCoordinator = (application as GatewayApp).tiviMateUpdateCoordinator
        installManager = ApkInstallManager(this)

        textGatewayBuildInfo = findViewById(R.id.textGatewayBuildInfo)
        textTiviMateInstalled = findViewById(R.id.textTiviMateInstalled)
        textTiviMateLatest = findViewById(R.id.textTiviMateLatest)
        textTiviMateStatus = findViewById(R.id.textTiviMateStatus)
        textTiviMateUpdateBadge = findViewById(R.id.textTiviMateUpdateBadge)
        buttonCheckTiviMateUpdate = findViewById(R.id.buttonCheckTiviMateUpdate)
        buttonUpdateTiviMateNow = findViewById(R.id.buttonUpdateTiviMateNow)
        buttonOpenReleases = findViewById(R.id.buttonOpenReleases)
        textTiviMatePlaylistState = findViewById(R.id.textTiviMatePlaylistState)

        bindGatewayInfo()
        wireMigrationUi()
        tiviMateUpdateCoordinator.addListener(updateListener)
        renderTiviMateState(tiviMateUpdateCoordinator.currentResult())
        refreshTiviMatePlaylistState()

        buttonCheckTiviMateUpdate.setOnClickListener {
            textTiviMateStatus.text = getString(R.string.tivimate_update_checking)
            tiviMateUpdateCoordinator.checkForUpdate(this, manual = true) { result ->
                result.onFailure {
                    textTiviMateStatus.text = getString(
                        R.string.tivimate_update_check_failed,
                        it.message ?: "error",
                    )
                }
            }
        }
        buttonUpdateTiviMateNow.setOnClickListener { onUpdateNowClicked() }
        buttonOpenReleases.setOnClickListener { openReleasesPage() }
        findViewById<MaterialButton>(R.id.buttonBack).setOnClickListener { finish() }

        tiviMateUpdateCoordinator.refreshInstalled(this) { result ->
            if (result == null) {
                tiviMateUpdateCoordinator.checkForUpdate(this, manual = false) { checkResult ->
                    checkResult.onFailure {
                        textTiviMateStatus.text = getString(
                            R.string.tivimate_update_check_failed,
                            it.message ?: "error",
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTiviMatePlaylistState()
    }

    override fun onDestroy() {
        tiviMateUpdateCoordinator.removeListener(updateListener)
        super.onDestroy()
    }

    private fun bindGatewayInfo() {
        val built = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME))
        textGatewayBuildInfo.text = getString(
            R.string.settings_build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_HASH,
            BuildConfig.BUILD_TYPE,
            built,
        )
    }

    private fun wireMigrationUi() {
        val isDebugPackage = BuildConfig.APPLICATION_ID.endsWith(".debug")
        findViewById<View>(R.id.layoutAboutMigrationNote).visibility =
            if (isDebugPackage) View.GONE else View.VISIBLE
        val graduateButton = findViewById<MaterialButton>(R.id.buttonGraduateReleaseAbout)
        if (isDebugPackage) {
            graduateButton.visibility = View.VISIBLE
            graduateButton.setOnClickListener {
                (application as GatewayApp).appUpdateCoordinator.graduateToRelease(this)
            }
        } else {
            graduateButton.visibility = View.GONE
        }
    }

    private fun renderTiviMateState(result: TiviMateUpdateCheckResult?) {
        if (result == null) {
            textTiviMateInstalled.text = getString(R.string.about_tivimate_installed_checking)
            textTiviMateLatest.text = getString(R.string.about_tivimate_latest_checking)
            textTiviMateStatus.text = getString(R.string.tivimate_update_checking)
            textTiviMateUpdateBadge.visibility = View.GONE
            buttonUpdateTiviMateNow.visibility = View.GONE
            return
        }

        val probe = result.probe
        textTiviMateInstalled.text = when (probe.variant) {
            TiviMateInstalledVariant.NOT_INSTALLED ->
                getString(R.string.about_tivimate_not_installed)
            TiviMateInstalledVariant.STEP_DADDY ->
                getString(
                    R.string.about_tivimate_installed_patch,
                    probe.patchVersion ?: probe.versionName.orEmpty(),
                )
            TiviMateInstalledVariant.PLAIN_MOD ->
                getString(
                    R.string.about_tivimate_x2_mod,
                    probe.versionName.orEmpty(),
                )
            TiviMateInstalledVariant.UNKNOWN ->
                getString(
                    R.string.about_tivimate_unknown,
                    probe.versionName.orEmpty(),
                )
        }

        val latest = result.latest.manifest
        textTiviMateLatest.text = getString(
            R.string.about_tivimate_latest_version,
            latest.versionName,
            latest.versionCode,
        )

        textTiviMateUpdateBadge.visibility =
            if (result.updateAvailable && probe.variant == TiviMateInstalledVariant.STEP_DADDY) {
                View.VISIBLE
            } else {
                View.GONE
            }
        buttonUpdateTiviMateNow.visibility =
            if (result.updateAvailable && probe.variant == TiviMateInstalledVariant.STEP_DADDY) {
                View.VISIBLE
            } else {
                View.GONE
            }
        buttonCheckTiviMateUpdate.visibility =
            if (probe.variant == TiviMateInstalledVariant.STEP_DADDY) View.VISIBLE else View.GONE

        textTiviMateStatus.text = when {
            result.updateAvailable && probe.variant == TiviMateInstalledVariant.NOT_INSTALLED ->
                getString(R.string.about_tivimate_status_install_available)
            result.updateAvailable && probe.variant == TiviMateInstalledVariant.PLAIN_MOD ->
                getString(R.string.about_tivimate_status_x2_mod_ready)
            result.updateAvailable && probe.variant == TiviMateInstalledVariant.UNKNOWN ->
                getString(R.string.about_tivimate_status_x2_mod_ready)
            result.updateAvailable ->
                getString(R.string.about_tivimate_status_update_available)
            probe.variant == TiviMateInstalledVariant.NOT_INSTALLED ->
                getString(R.string.about_tivimate_status_not_installed_latest)
            probe.variant == TiviMateInstalledVariant.PLAIN_MOD ||
                probe.variant == TiviMateInstalledVariant.UNKNOWN ->
                getString(R.string.about_tivimate_status_x2_mod_ready)
            else -> getString(R.string.about_tivimate_status_up_to_date)
        }
        refreshTiviMatePlaylistState()
    }

    private fun refreshTiviMatePlaylistState() {
        if (!TiviMateController.isInstalled(this)) {
            textTiviMatePlaylistState.visibility = View.GONE
            return
        }
        val probe = TiviMateController.probeState()
        val line = TiviMatePlaylistStateHelper.aboutPlaylistLine(this, probe.state)
        if (line.isNullOrBlank()) {
            textTiviMatePlaylistState.visibility = View.GONE
            return
        }
        textTiviMatePlaylistState.text = getString(R.string.about_tivimate_playlist_state, line)
        textTiviMatePlaylistState.visibility = View.VISIBLE
    }

    private fun onUpdateNowClicked() {
        val result = tiviMateUpdateCoordinator.currentResult()
        if (result == null) {
            tiviMateUpdateCoordinator.checkForUpdate(this, manual = true)
            return
        }
        if (!installManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.install_apps_unknown_sources_hint, Toast.LENGTH_LONG).show()
            installManager.openInstallPermissionSettings()
            return
        }
        tiviMateUpdateCoordinator.promptUpdateIfNeeded(this, result)
    }

    private fun openReleasesPage() {
        val url = AppUpdateRepository.releasesPageUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.about_open_releases_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
