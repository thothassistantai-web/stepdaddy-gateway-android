package com.nova.stepdaddylivehd.gateway.upstream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class LogoResolver(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val byChannelId = mutableMapOf<String, String>()
    private val compactToIds = mutableMapOf<String, MutableList<String>>()
    @Volatile
    private var loaded = false

    init {
        scope.launch {
            runCatching { loadCsv() }
                .onSuccess { loaded = true }
                .onFailure { exc -> Log.w(TAG, "Logo DB load failed", exc) }
        }
    }

    fun resolveLogoUrl(apiBase: String, channelName: String, tvgId: String?): String {
        if (!loaded) {
            return defaultLogoUrl(apiBase)
        }
        val remote = resolveRemoteLogo(tvgId)
        if (remote.isNullOrBlank()) {
            return defaultLogoUrl(apiBase)
        }
        if (remote.startsWith(apiBase)) {
            return remote
        }
        return "${apiBase.trimEnd('/')}/logo/${UrlSafeBase64.encode(remote)}"
    }

    fun defaultLogoUrl(apiBase: String): String =
        "${apiBase.trimEnd('/')}/ui/default-channel.svg"

    private fun resolveRemoteLogo(tvgId: String?): String? {
        for (variant in tvgIdVariants(tvgId)) {
            byChannelId[variant]?.let { return it }
        }
        return null
    }

    private fun tvgIdVariants(tvgId: String?): List<String> {
        if (tvgId.isNullOrBlank()) {
            return emptyList()
        }
        val variants = linkedSetOf(tvgId)
        for (suffix in listOf(".us2", ".us_locals1", ".us_locals2", ".uk1", ".ae1")) {
            if (tvgId.endsWith(suffix)) {
                val repl = if (suffix.startsWith(".us")) ".us" else suffix.take(3)
                variants += tvgId.dropLast(suffix.length) + repl
            }
        }
        val compact = compactTvgId(tvgId)
        compactToIds[compact]?.forEach { variants += it }
        return variants.toList()
    }

    private fun loadCsv() {
        val best = mutableMapOf<String, Triple<Int, Boolean, String>>()
        appContext.assets.open("logos_db_cache.csv").bufferedReader().use { reader ->
            reader.readLine()
            reader.forEachLine { line ->
                val row = parseCsvLine(line)
                val channelId = row["channel"].orEmpty().trim()
                val logo = row["url"].orEmpty().trim()
                if (channelId.isEmpty() || !logo.startsWith("https://")) {
                    return@forEachLine
                }
                val inUse = row["in_use"].orEmpty().equals("TRUE", ignoreCase = true)
                val width = row["width"]?.toIntOrNull() ?: 0
                val prev = best[channelId]
                if (prev == null ||
                    (inUse && !prev.second) ||
                    (inUse == prev.second && width > prev.first)
                ) {
                    best[channelId] = Triple(width, inUse, logo)
                }
            }
        }
        synchronized(byChannelId) {
            byChannelId.clear()
            compactToIds.clear()
            best.forEach { (channelId, triple) ->
                byChannelId[channelId] = triple.third
                val compact = compactTvgId(channelId)
                if (compact.isNotEmpty()) {
                    compactToIds.getOrPut(compact) { mutableListOf() }.add(channelId)
                }
            }
        }
        Log.i(TAG, "Loaded ${byChannelId.size} logo entries")
    }

    private fun parseCsvLine(line: String): Map<String, String> {
        val cols = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cols += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        cols += current.toString()
        val keys = listOf("channel", "feed", "in_use", "tags", "width", "height", "format", "url")
        return keys.mapIndexedNotNull { index, key ->
            cols.getOrNull(index)?.let { key to it.trim('"') }
        }.toMap()
    }

    private fun compactTvgId(tvgId: String): String =
        tvgId.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    companion object {
        private const val TAG = "LogoResolver"
    }
}
