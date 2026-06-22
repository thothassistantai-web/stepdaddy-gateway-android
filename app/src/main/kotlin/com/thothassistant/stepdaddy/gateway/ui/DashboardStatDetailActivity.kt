package com.thothassistant.stepdaddy.gateway.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.ActivityDashboardStatDetailBinding
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import com.thothassistant.stepdaddy.gateway.ui.dashboard.DashboardStatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashboardStatDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardStatDetailBinding
    private lateinit var environment: com.thothassistant.stepdaddy.gateway.GatewayEnvironment
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardStatDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        environment = (application as GatewayApp).gatewayEnvironment
        binding.buttonBack.setOnClickListener { finish() }
        val type = runCatching {
            DashboardStatType.valueOf(
                intent.getStringExtra(EXTRA_STAT_TYPE) ?: DashboardStatType.STATUS.name,
            )
        }.getOrDefault(DashboardStatType.STATUS)
        binding.textTitle.text = titleFor(type)
        loadAndRender(type)
    }

    private fun loadAndRender(type: DashboardStatType) {
        binding.textSummary.text = getString(R.string.stat_detail_loading)
        binding.textDetails.text = ""
        binding.layoutActions.removeAllViews()
        lifecycleScope.launch {
            val health = fetchHealth()
            if (health == null) {
                binding.textSummary.text = getString(R.string.stat_detail_unreachable)
                addAction(getString(R.string.stat_action_open_settings)) { openSettings() }
                return@launch
            }
            render(type, health)
        }
    }

    private suspend fun fetchHealth(): HealthResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${environment.loopbackBase()}/health"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext null
                json.decodeFromString<HealthResponse>(body)
            }
        }.getOrNull()
    }

    private fun render(type: DashboardStatType, health: HealthResponse) {
        val progress = health.loadProgress
        binding.textSummary.text = when (type) {
            DashboardStatType.CHANNELS -> summaryChannels(health, progress?.channels)
            DashboardStatType.PROGRAMS -> summaryPrograms(health, progress?.programs)
            DashboardStatType.STATUS -> summaryStatus(health, progress?.status)
            DashboardStatType.SOURCES -> summarySources(health, progress?.sources)
        }
        binding.textDetails.text = detailsFor(type, health)
        when (type) {
            DashboardStatType.CHANNELS -> {
                addAction(getString(R.string.stat_action_refresh_channels)) { postAction("refresh-channels") }
                addAction(getString(R.string.stat_action_refresh_supplements)) { postAction("refresh-supplements") }
            }
            DashboardStatType.PROGRAMS -> {
                addAction(getString(R.string.stat_action_refresh_epg)) { postAction("refresh-epg") }
                addAction(getString(R.string.stat_action_open_settings)) { openSettings() }
            }
            DashboardStatType.STATUS -> {
                addAction(getString(R.string.stat_action_restart_gateway)) { postAction("restart-full") }
                addAction(getString(R.string.stat_action_open_settings)) { openSettings() }
            }
            DashboardStatType.SOURCES -> {
                addAction(getString(R.string.stat_action_refresh_supplements)) { postAction("refresh-supplements") }
                addAction(getString(R.string.stat_action_open_settings)) { openSettings() }
            }
        }
    }

    private fun summaryChannels(
        health: HealthResponse,
        progress: com.thothassistant.stepdaddy.gateway.model.LoadProgress?,
    ): String {
        val total = health.providers?.total ?: health.channels
        return buildString {
            appendLine(getString(R.string.stat_detail_channels_summary, total))
            progress?.let {
                appendLine(getString(R.string.stat_detail_phase, it.phase, it.percent))
                it.detail?.let { d -> appendLine(d) }
            }
            appendLine()
            append(getString(R.string.stat_detail_daddylive, health.channels))
            appendLine()
            append(getString(R.string.stat_detail_supplements, health.supplementChannels))
        }
    }

    private fun summaryPrograms(
        health: HealthResponse,
        progress: com.thothassistant.stepdaddy.gateway.model.LoadProgress?,
    ): String {
        return buildString {
            if (!health.gatewayEpgEnabled) {
                appendLine(getString(R.string.stat_detail_external_epg))
                append(getString(R.string.stat_detail_external_feeds, health.epgSourceCount))
            } else {
                appendLine(
                    getString(
                        R.string.stat_detail_programmes,
                        health.epgProgrammeCount,
                        health.epgReady,
                    ),
                )
                progress?.let {
                    appendLine(getString(R.string.stat_detail_phase, it.phase, it.percent))
                    it.detail?.let { d -> appendLine(d) }
                }
                health.epgCoverage?.let { cov ->
                    appendLine()
                    append(getString(R.string.stat_detail_epg_coverage, cov.withRealProgrammes, cov.withTvgId))
                }
            }
        }
    }

    private fun summaryStatus(
        health: HealthResponse,
        progress: com.thothassistant.stepdaddy.gateway.model.LoadProgress?,
    ): String {
        return buildString {
            appendLine(getString(R.string.stat_detail_gateway_version, health.version))
            appendLine(getString(R.string.stat_detail_upstream, health.upstreamBaseUrl))
            progress?.let {
                appendLine(getString(R.string.stat_detail_phase, it.phase, it.percent))
                it.detail?.let { d -> appendLine(d) }
            }
            health.healing?.let { h ->
                appendLine()
                append(getString(R.string.stat_detail_healing, h.lastAction, h.streamFailures))
            }
        }
    }

    private fun summarySources(
        health: HealthResponse,
        progress: com.thothassistant.stepdaddy.gateway.model.LoadProgress?,
    ): String {
        val p = health.providers
        return buildString {
            progress?.let {
                appendLine(getString(R.string.stat_detail_phase, it.phase, it.percent))
                it.detail?.let { d -> appendLine(d) }
            }
            if (p != null) {
                appendLine()
                appendLine("DaddyLive: ${numberFormat.format(p.daddylive)}")
                appendLine("IPTV-org: ${numberFormat.format(p.iptvOrg)}")
                appendLine("NTV.cx: ${numberFormat.format(p.ntvCx)}")
                appendLine("Sports: ${numberFormat.format(p.sports)}")
                appendLine("MoveOnJoy: ${numberFormat.format(p.moveOnJoy)}")
                append("Adult Swim: ${numberFormat.format(p.adultSwim)}")
            }
        }
    }

    private fun detailsFor(type: DashboardStatType, health: HealthResponse): String {
        return when (type) {
            DashboardStatType.CHANNELS -> buildString {
                health.topCategories.forEach { cat ->
                    appendLine("${cat.groupTitle}: ${cat.count}")
                }
                health.providers?.let { p ->
                    appendLine()
                    append("providers.total=${p.total}")
                }
            }
            DashboardStatType.PROGRAMS -> buildString {
                appendLine("epgReady=${health.epgReady}")
                appendLine("epgProgrammeCount=${health.epgProgrammeCount}")
                appendLine("epgAgeSeconds=${health.epgAgeSeconds}")
                health.epgCoverage?.let { c ->
                    appendLine("mappedPercent=${c.mappedPercent}")
                    appendLine("withRealProgrammes=${c.withRealProgrammes}")
                    appendLine("placeholderProgrammes=${c.placeholderProgrammes}")
                }
            }
            DashboardStatType.STATUS -> buildString {
                appendLine("ok=${health.ok}")
                appendLine("starting=${health.starting}")
                appendLine("port=${health.port}")
                appendLine("baseUrl=${health.baseUrl}")
                health.healing?.recentActions?.forEach { appendLine(it) }
            }
            DashboardStatType.SOURCES -> buildString {
                health.supplement?.let { s ->
                    appendLine("sidecarEnabled=${s.sidecarEnabled}")
                    appendLine("sportsEnabled=${s.sportsEnabled}")
                    appendLine("iptvOrgEnabled=${s.iptvOrgEnabled}")
                    appendLine("iptvOrgPlaylistsFetched=${s.iptvOrgPlaylistsFetched}")
                    appendLine("iptvOrgPlaylistsFailed=${s.iptvOrgPlaylistsFailed}")
                    appendLine("ntvCxEnabled=${s.ntvCxEnabled}")
                    appendLine("adultSwimEnabled=${s.adultSwimEnabled}")
                }
            }
        }
    }

    private fun titleFor(type: DashboardStatType): String = when (type) {
        DashboardStatType.CHANNELS -> getString(R.string.stat_detail_title_channels)
        DashboardStatType.PROGRAMS -> getString(R.string.stat_detail_title_programs)
        DashboardStatType.STATUS -> getString(R.string.stat_detail_title_status)
        DashboardStatType.SOURCES -> getString(R.string.stat_detail_title_sources)
    }

    private fun addAction(label: String, onClick: () -> Unit) {
        val button = MaterialButton(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 }
            setOnClickListener { onClick() }
        }
        binding.layoutActions.addView(button)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun postAction(path: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "${environment.loopbackBase()}/api/v1/actions/$path"
                    val request = Request.Builder().url(url).post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
                    http.newCall(request).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            Toast.makeText(
                this@DashboardStatDetailActivity,
                if (ok) R.string.stat_action_sent else R.string.stat_action_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) loadAndRender(
                DashboardStatType.valueOf(intent.getStringExtra(EXTRA_STAT_TYPE) ?: DashboardStatType.STATUS.name),
            )
        }
    }

    companion object {
        const val EXTRA_STAT_TYPE = "stat_type"
    }
}
