package com.nova.stepdaddylivehd.gateway.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nova.stepdaddylivehd.gateway.BuildConfig
import com.nova.stepdaddylivehd.gateway.GatewayApp
import com.nova.stepdaddylivehd.gateway.GatewayStartHelper
import com.nova.stepdaddylivehd.gateway.R
import com.nova.stepdaddylivehd.gateway.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var environment: com.nova.stepdaddylivehd.gateway.GatewayEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        loadFields()
        binding.buttonSave.setOnClickListener { saveAndFinish() }
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonSave.requestFocus()
    }

    private fun loadFields() {
        binding.editPort.setText(environment.port.toString())
        binding.editRemoteGatewayUrl.setText(environment.remoteGatewayUrl)
        binding.editDlhdUrl.setText(environment.dlhdBaseUrl)
        binding.editMirrorUrls.setText(environment.mirrorUrls.joinToString(","))
        binding.editSupplementUrl.setText(environment.supplementBaseUrl)
        binding.switchEmbeddedSidecar.isChecked = environment.embeddedSidecarEnabled
        binding.switchSupplementSports.isChecked = environment.supplementSportsEnabled
        binding.switchSupplementIptvOrg.isChecked = environment.supplementIptvOrgEnabled
        binding.switchIptvOrgEpg.isChecked = environment.iptvOrgEpgEnabled
        binding.editIptvOrgEpgUrl.setText(environment.iptvOrgEpgUrl)
        binding.switchAutoStart.isChecked = environment.autoStartOnLaunch
        binding.switchLaunchTivimate.isChecked = environment.launchTivimateOnReady
        binding.switchBoot.isChecked = environment.startOnBoot
        binding.switchTivimateWatch.isChecked = environment.tivimateWatchEnabled
        val built = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME))
        binding.textBuildInfo.text = getString(
            R.string.settings_build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_HASH,
            BuildConfig.BUILD_TYPE,
            built,
        )
    }

    private fun saveAndFinish() {
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, R.string.settings_port_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        environment.port = port
        environment.remoteGatewayUrl = binding.editRemoteGatewayUrl.text?.toString().orEmpty()
        environment.dlhdBaseUrl = binding.editDlhdUrl.text?.toString().orEmpty()
        environment.mirrorUrls = binding.editMirrorUrls.text?.toString()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        environment.supplementBaseUrl = binding.editSupplementUrl.text?.toString().orEmpty()
        environment.embeddedSidecarEnabled = binding.switchEmbeddedSidecar.isChecked
        environment.supplementSportsEnabled = binding.switchSupplementSports.isChecked
        environment.supplementIptvOrgEnabled = binding.switchSupplementIptvOrg.isChecked
        environment.iptvOrgEpgEnabled = binding.switchIptvOrgEpg.isChecked
        environment.iptvOrgEpgUrl = binding.editIptvOrgEpgUrl.text?.toString().orEmpty()
        environment.autoStartOnLaunch = binding.switchAutoStart.isChecked
        environment.launchTivimateOnReady = binding.switchLaunchTivimate.isChecked
        environment.startOnBoot = binding.switchBoot.isChecked
        environment.tivimateWatchEnabled = binding.switchTivimateWatch.isChecked
        if (environment.startOnBoot) {
            GatewayStartHelper.schedulePeriodicEnsureAlive(this)
        } else {
            GatewayStartHelper.cancelPeriodicEnsureAlive(this)
        }
        Toast.makeText(this, R.string.settings_saved_restart_hint, Toast.LENGTH_LONG).show()
        finish()
    }
}
