package com.thothassistant.stepdaddy.gateway.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.upstream.IptvOrgStreamsConfig

/** Per-playlist enable/disable for the iptv-org supplement provider. */
class IptvOrgPlaylistSettingsActivity : AppCompatActivity() {
    private lateinit var environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment
    private val checkboxes = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_iptv_org_playlists)
        environment = (application as GatewayApp).gatewayEnvironment

        val container = findViewById<LinearLayout>(R.id.layoutIptvOrgPlaylistChecks)
        val enabled = environment.iptvOrgEnabledPlaylists

        IptvOrgStreamsConfig.PLAYLIST_FILES.forEach { filename ->
            val check = CheckBox(this).apply {
                text = getString(
                    R.string.settings_iptv_org_playlist_item,
                    IptvOrgStreamsConfig.playlistDisplayName(filename),
                    filename,
                )
                isChecked = filename in enabled
                tag = filename
                setTextColor(getColor(R.color.on_background))
            }
            checkboxes[filename] = check
            container.addView(check)
        }

        findViewById<MaterialButton>(R.id.buttonSelectAllPlaylists).setOnClickListener {
            checkboxes.values.forEach { it.isChecked = true }
        }
        findViewById<MaterialButton>(R.id.buttonDeselectAllPlaylists).setOnClickListener {
            checkboxes.values.forEach { it.isChecked = false }
        }
        findViewById<MaterialButton>(R.id.buttonSaveIptvOrgPlaylists).setOnClickListener { saveAndFinish() }
        findViewById<MaterialButton>(R.id.buttonBackIptvOrgPlaylists).setOnClickListener { finish() }
    }

    private fun saveAndFinish() {
        val selected = checkboxes.filterValues { it.isChecked }.keys
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.settings_iptv_org_playlists_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        environment.iptvOrgEnabledPlaylists = selected
        Toast.makeText(this, R.string.settings_iptv_org_playlists_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
