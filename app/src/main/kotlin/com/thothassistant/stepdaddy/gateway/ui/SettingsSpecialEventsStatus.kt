package com.thothassistant.stepdaddy.gateway.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.ServerService
import com.thothassistant.stepdaddy.gateway.databinding.ActivitySettingsBinding
import com.thothassistant.stepdaddy.gateway.model.HealthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object SettingsSpecialEventsStatus {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun wire(binding: ActivitySettingsBinding) {
        binding.switchSupplementSports.setOnCheckedChangeListener { _, _ ->
            updateVisibility(binding)
        }
        updateVisibility(binding)
    }

    fun startPolling(activity: AppCompatActivity, binding: ActivitySettingsBinding, healthUrl: () -> String): Job {
        return activity.lifecycleScope.launch {
            while (isActive) {
                refresh(activity, binding, healthUrl)
                delay(10_000L)
            }
        }
    }

    private fun updateVisibility(binding: ActivitySettingsBinding) {
        binding.layoutSpecialEventsHealth.visibility =
            if (binding.switchSupplementSports.isChecked) View.VISIBLE else View.GONE
    }

    private suspend fun refresh(
        activity: AppCompatActivity,
        binding: ActivitySettingsBinding,
        healthUrl: () -> String,
    ) {
        updateVisibility(binding)
        if (!binding.switchSupplementSports.isChecked) return
        if (!ServerService.isServiceActive) {
            binding.textSpecialEventsHealthStatus.text =
                activity.getString(R.string.settings_special_events_status_offline)
            binding.textSpecialEventsHealthStatus.setTextColor(
                activity.getColor(R.color.on_background_muted),
            )
            binding.textSpecialEventsHealthCounts.text = ""
            binding.textSpecialEventsHealthScrape.text = ""
            return
        }
        binding.textSpecialEventsHealthStatus.text =
            activity.getString(R.string.settings_special_events_status_loading)
        val health = fetchHealth(healthUrl)
        val supplement = health?.supplement
        binding.textSpecialEventsHealthStatus.text =
            SpecialEventsDashboardRenderer.statusLabel(activity, supplement)
        binding.textSpecialEventsHealthStatus.setTextColor(
            SpecialEventsDashboardRenderer.statusColor(activity, supplement),
        )
        binding.textSpecialEventsHealthCounts.text =
            SpecialEventsDashboardRenderer.countsLine(activity, supplement)
        binding.textSpecialEventsHealthScrape.text =
            SpecialEventsDashboardRenderer.lastScrapeLine(activity, supplement)
    }

    private suspend fun fetchHealth(healthUrl: () -> String): HealthResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val base = healthUrl()
            val url = if (base.contains("?")) "$base&lite=1" else "$base?lite=1"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext null
                json.decodeFromString<HealthResponse>(body)
            }
        }.getOrNull()
    }
}
