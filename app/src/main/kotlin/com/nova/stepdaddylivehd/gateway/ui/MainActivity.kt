package com.nova.stepdaddylivehd.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nova.stepdaddylivehd.gateway.GatewayApp
import com.nova.stepdaddylivehd.gateway.PermissionHelper
import com.nova.stepdaddylivehd.gateway.R
import com.nova.stepdaddylivehd.gateway.ServerService
import com.nova.stepdaddylivehd.gateway.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var environment: com.nova.stepdaddylivehd.gateway.GatewayEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        requestRuntimePermissions()
        bindUrls()
        updateEpgStatus()
        binding.buttonStart.setOnClickListener { startServer() }
        binding.buttonStop.setOnClickListener { stopServer() }
        binding.buttonCopyPlaylist.setOnClickListener { copyUrl(playlistUrl()) }
        binding.buttonCopyEpg.setOnClickListener { copyUrl(epgUrl()) }
        binding.switchBoot.isChecked = environment.startOnBoot
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            environment.startOnBoot = checked
        }
        updateStatus()
        updateEpgStatus()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        updateStatus()
        updateEpgStatus()
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    private fun requestRuntimePermissions() {
        PermissionHelper.requestNotificationPermission(this)
        PermissionHelper.requestOverlayPermission(this)
        if (!PermissionHelper.isBatteryOptimizationIgnored(this)) {
            PermissionHelper.requestBatteryOptimizationExemption(this)
        }
        PermissionHelper.requestExactAlarmPermission(this)
    }

    private fun bindUrls() {
        binding.textPlaylistUrl.text = playlistUrl()
        binding.textEpgUrl.text = epgUrl()
        binding.textHealthUrl.text = healthUrl()
        binding.textPort.text = getString(R.string.label_port_value, environment.port)
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
        val epgManager = (application as GatewayApp).epgManager
        binding.textEpgStatus.text = when {
            epgManager.meta.state == "building" -> getString(R.string.status_epg_building)
            epgManager.epgReady() -> getString(R.string.status_epg_ready, epgManager.programmeCount())
            else -> getString(R.string.status_epg_pending)
        }
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("stepdaddy_url", url))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        @Volatile
        var isInForeground: Boolean = false
    }
}
