package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.Channel

/** Browse DaddyLive channels that have (or need) supplement backup streams. */
class ChannelBackupsActivity : AppCompatActivity() {
    private lateinit var app: GatewayApp
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_backups)
        app = application as GatewayApp
        list = findViewById(R.id.layoutChannelBackupsList)
        status = findViewById(R.id.textChannelBackupsStatus)
        search = findViewById(R.id.editChannelBackupsSearch)

        findViewById<MaterialButton>(R.id.buttonChannelBackupsSearch).setOnClickListener { render() }
        findViewById<MaterialButton>(R.id.buttonChannelBackupsBack).setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun render() {
        list.removeAllViews()
        val query = search.text?.toString()?.trim().orEmpty()
        val fallbacks = app.supplementSource.daddyChannelFallbacksAll()
        val daddy = app.daddyLiveChannels()
        val rows = buildRows(daddy, fallbacks, query)
        status.text = getString(
            R.string.channel_backups_status,
            rows.size,
            fallbacks.values.sumOf { it.size },
        )
        if (rows.isEmpty()) {
            list.addView(muted(getString(R.string.channel_backups_empty)))
            return
        }
        for (row in rows) {
            list.addView(channelButton(row))
        }
    }

    private fun buildRows(
        daddy: List<Channel>,
        fallbacks: Map<String, List<*>>,
        query: String,
    ): List<Channel> {
        val byId = daddy.associateBy { it.id }
        val withBackups = fallbacks.keys.mapNotNull { byId[it] }
        val pool = if (query.isBlank()) {
            withBackups.ifEmpty { daddy.take(40) }
        } else {
            val q = query.lowercase()
            daddy.filter {
                it.name.lowercase().contains(q) ||
                    it.id.contains(q) ||
                    it.tvgId.orEmpty().lowercase().contains(q)
            }.take(60)
        }
        return pool.sortedBy { it.name.lowercase() }
    }

    private fun channelButton(channel: Channel): MaterialButton {
        val count = app.supplementSource.daddyChannelFallbacks(channel.id).size
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (8 * resources.displayMetrics.density).toInt() }
            minHeight = (52 * resources.displayMetrics.density).toInt()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = if (count > 0) {
                getString(R.string.channel_backups_row_with_count, channel.name, count)
            } else {
                channel.name
            }
            setOnClickListener {
                startActivity(
                    Intent(this@ChannelBackupsActivity, ChannelBackupDetailActivity::class.java)
                        .putExtra(ChannelBackupDetailActivity.EXTRA_CHANNEL_ID, channel.id),
                )
            }
        }
    }

    private fun muted(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.on_background_muted))
            textSize = 13f
        }
}
