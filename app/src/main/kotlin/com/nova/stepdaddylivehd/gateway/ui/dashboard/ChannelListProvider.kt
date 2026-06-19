package com.nova.stepdaddylivehd.gateway.ui.dashboard

import com.nova.stepdaddylivehd.gateway.GatewayEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ChannelListProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun loadSorted(environment: GatewayEnvironment): List<TuneChannel> =
        withContext(Dispatchers.IO) {
            val url = "${environment.loopbackBase()}/tivimate-playlist.m3u8"
            val request = Request.Builder().url(url).get().build()
            val body = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    response.body?.string().orEmpty()
                }
            }.getOrDefault("")
            parseM3u(body)
        }

    fun parseM3u(body: String): List<TuneChannel> {
        if (body.isBlank()) return emptyList()
        val channels = ArrayList<TuneChannel>()
        var pendingName: String? = null
        var pendingNumber: Int? = null

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingName = line.substringAfterLast(',').trim().ifEmpty { null }
                pendingNumber = extractAttr(line, "tvg-chno")?.toIntOrNull()
            } else if (!line.startsWith("#") && line.isNotEmpty()) {
                val streamLine = line.substringBefore('|')
                val id = streamLine.substringAfterLast('/')
                    .removeSuffix(".m3u8")
                    .trim()
                if (id.isNotEmpty()) {
                    val name = pendingName ?: "Channel $id"
                    val number = pendingNumber ?: id.toIntOrNull() ?: channels.size + 1
                    channels += TuneChannel(id = id, name = name, number = number)
                }
                pendingName = null
                pendingNumber = null
            }
        }
        return channels.sortedBy { it.number }
    }

    private fun extractAttr(extinf: String, key: String): String? {
        val pattern = """$key="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(extinf)?.groupValues?.getOrNull(1)
    }
}
