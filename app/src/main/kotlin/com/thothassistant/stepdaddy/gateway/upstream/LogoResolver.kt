package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.upstream.GatewayConfig.USER_AGENT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class LogoResolver(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadGate = CompletableDeferred<Unit>()
    private val byChannelId = mutableMapOf<String, String>()
    private val compactToIds = mutableMapOf<String, MutableList<String>>()
    private val dottedToIds = mutableMapOf<String, MutableList<String>>()
    private val nameToId = mutableMapOf<String, String>()
    private val fuzzyBuckets = mutableMapOf<String, MutableList<String>>()
    private val overridesByName = mutableMapOf<String, String>()
    private val placeholderCache = mutableMapOf<String, ByteArray>()
    @Volatile
    private var loaded = false

    init {
        scope.launch {
            runCatching { loadAll() }
                .onSuccess {
                    loaded = true
                    loadGate.complete(Unit)
                    Log.i(TAG, "Logo DB ready: ${byChannelId.size} logos, ${nameToId.size} names, ${overridesByName.size} overrides")
                }
                .onFailure { exc ->
                    Log.w(TAG, "Logo DB load failed", exc)
                    loadGate.completeExceptionally(exc)
                }
        }
    }

    suspend fun awaitLoaded(timeoutMs: Long = 60_000L) {
        if (loaded) return
        withTimeout(timeoutMs) { loadGate.await() }
    }

    fun isLoaded(): Boolean = loaded

    fun stats(): LogoStats = synchronized(this) {
        LogoStats(
            logoEntries = byChannelId.size,
            nameEntries = nameToId.size,
            overrides = overridesByName.size,
            loaded = loaded,
        )
    }

    /**
     * Resolve a playlist-safe logo URL. Always returns a URL that the gateway serves with HTTP 200.
     */
    suspend fun resolveLogoUrl(
        apiBase: String,
        channelName: String,
        tvgId: String?,
        metaLogo: String? = null,
    ): String {
        awaitLoaded()
        val remote = resolveRemoteLogo(channelName, tvgId, metaLogo)
        if (!remote.isNullOrBlank()) {
            return proxyUrl(apiBase, remote)
        }
        return placeholderUrl(apiBase, channelName)
    }

    fun resolveLogoUrlBlocking(
        apiBase: String,
        channelName: String,
        tvgId: String?,
        metaLogo: String? = null,
    ): String {
        if (!loaded) {
            return placeholderUrl(apiBase, channelName)
        }
        val remote = resolveRemoteLogo(channelName, tvgId, metaLogo)
        if (!remote.isNullOrBlank()) {
            return proxyUrl(apiBase, remote)
        }
        return placeholderUrl(apiBase, channelName)
    }

    /**
     * Fast logo URL for M3U generation — exact id/name hits only (no fuzzy Levenshtein scan).
     */
    fun resolvePlaylistLogoUrl(
        apiBase: String,
        channelName: String,
        tvgId: String?,
        metaLogo: String? = null,
        channelLogo: String? = null,
    ): String {
        metaLogo?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { return proxyUrl(apiBase, it) }
        if (loaded) {
            resolveRemoteLogoExact(channelName, tvgId)?.let { return proxyUrl(apiBase, it) }
        }
        channelLogo?.trim()?.takeIf { it.isNotEmpty() && !isGatewayPlaceholder(apiBase, it) }?.let { logo ->
            return if (logo.startsWith("http://") || logo.startsWith("https://")) {
                proxyUrl(apiBase, logo)
            } else {
                logo
            }
        }
        if (!loaded) {
            return placeholderUrl(apiBase, channelName)
        }
        return placeholderUrl(apiBase, channelName)
    }

    /** True when exact DB / override lookup would yield a remote logo (playlist-safe). */
    fun hasResolvableLogo(channelName: String, tvgId: String?, metaLogo: String? = null): Boolean {
        metaLogo?.trim()?.takeIf { it.startsWith("http") }?.let { return true }
        return resolveRemoteLogoExact(channelName, tvgId) != null
    }

    fun isGatewayPlaceholderUrl(apiBase: String, logo: String): Boolean =
        isGatewayPlaceholder(apiBase, logo)

    /**
     * Full lookup including fuzzy match — for offline backfill only, not M3U build hot path.
     */
    fun findBackfillLogo(channelName: String, tvgId: String?, metaLogo: String? = null): String? =
        resolveRemoteLogo(channelName, tvgId, metaLogo)

    fun removeRuntimeOverride(channelName: String): Boolean {
        val stripped = stripCategorySuffix(channelName.trim())
        synchronized(overridesByName) {
            var removed = false
            if (overridesByName.remove(channelName) != null) removed = true
            if (stripped != channelName && overridesByName.remove(stripped) != null) removed = true
            return removed
        }
    }

    fun putRuntimeOverride(channelName: String, remoteUrl: String) {
        synchronized(overridesByName) {
            overridesByName[channelName] = remoteUrl
            overridesByName[stripCategorySuffix(channelName)] = remoteUrl
        }
    }

    fun saveRuntimeOverrides(context: Context) {
        val file = LogoBackfillService.runtimeOverridesFile(context)
        file.parentFile?.mkdirs()
        val snapshot = synchronized(overridesByName) { overridesByName.toMap() }
        val bundled = loadBundledOverrideNames(context)
        val runtimeOnly = snapshot.filterKeys { it !in bundled }
        val json = JSONObject()
        runtimeOnly.forEach { (name, url) -> json.put(name, url) }
        file.writeText(json.toString())
        Log.i(TAG, "Saved ${runtimeOnly.size} runtime logo overrides")
    }

    private fun loadBundledOverrideNames(context: Context): Set<String> {
        return runCatching {
            val text = context.assets.open("channel_logo_overrides.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            buildSet { root.keys().forEach { add(it) } }
        }.getOrDefault(emptySet())
    }

    private fun loadRuntimeOverrides(context: Context) {
        val file = LogoBackfillService.runtimeOverridesFile(context)
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            synchronized(overridesByName) {
                root.keys().forEach { key ->
                    val url = root.optString(key).trim()
                    if (url.startsWith("http")) {
                        overridesByName[key] = url
                    }
                }
            }
            Log.i(TAG, "Loaded ${root.length()} runtime logo overrides")
        }.onFailure { exc ->
            Log.w(TAG, "Runtime logo overrides load failed", exc)
        }
    }

    private fun isGatewayPlaceholder(apiBase: String, logo: String): Boolean {
        val base = apiBase.trimEnd('/')
        return logo.startsWith("$base/ui/channel/") ||
            logo.startsWith("$base/ui/default-channel.svg")
    }

    fun defaultLogoUrl(apiBase: String): String =
        "${apiBase.trimEnd('/')}/ui/default-channel.svg"

    fun placeholderUrl(apiBase: String, channelName: String): String {
        val token = UrlSafeBase64.encode(channelName.trim())
        return "${apiBase.trimEnd('/')}/ui/channel/$token.svg"
    }

    fun placeholderSvg(channelName: String): ByteArray {
        val key = channelName.trim()
        synchronized(placeholderCache) {
            placeholderCache[key]?.let { return it }
            val bytes = ChannelPlaceholderSvg.render(key)
            placeholderCache[key] = bytes
            return bytes
        }
    }

    fun schedulePrewarm(channels: List<Pair<String, String?>>) {
        if (!loaded || channels.isEmpty()) return
        scope.launch {
            val urls = linkedSetOf<String>()
            channels.forEach { (name, tvgId) ->
                resolveRemoteLogo(name, tvgId, metaLogo = null)?.let { urls += it }
            }
            urls.forEach { url ->
                runCatching { prewarmOne(url) }
                    .onFailure { exc -> Log.d(TAG, "Logo prewarm skipped for $url: ${exc.message}") }
            }
            Log.i(TAG, "Logo prewarm finished for ${urls.size} unique upstream URLs")
        }
    }

    private suspend fun prewarmOne(upstreamUrl: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(upstreamUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                response.body?.bytes()
            }
        }
    }

    private fun proxyUrl(apiBase: String, remote: String): String {
        if (remote.startsWith(apiBase)) {
            return remote
        }
        if (remote.startsWith("/ui/")) {
            return "${apiBase.trimEnd('/')}$remote"
        }
        return "${apiBase.trimEnd('/')}/logo/${UrlSafeBase64.encode(remote)}"
    }

    private fun resolveRemoteLogo(channelName: String, tvgId: String?, metaLogo: String?): String? {
        metaLogo?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
        resolveRemoteLogoExact(channelName, tvgId)?.let { return it }
        val norm = TvgIdNormalizer.normalizeChannelName(channelName)
        fuzzyMatch(norm)?.let { cid ->
            byChannelId[cid]?.let { return it }
        }
        tokenFuzzyMatch(norm)?.let { cid ->
            byChannelId[cid]?.let { return it }
        }
        return null
    }

    private fun tokenFuzzyMatch(norm: String): String? {
        if (norm.length < 4 || nameToId.isEmpty()) return null
        val queryTokens = norm.split(' ').filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty()) return null
        var bestName: String? = null
        var bestScore = TOKEN_FUZZY_CUTOFF
        for (candidate in nameToId.keys) {
            val keyTokens = candidate.split(' ').filter { it.length > 1 }.toSet()
            if (keyTokens.isEmpty()) continue
            val intersection = queryTokens.intersect(keyTokens).size
            val union = queryTokens.union(keyTokens).size
            if (union == 0) continue
            val score = intersection.toDouble() / union.toDouble()
            if (score > bestScore) {
                bestScore = score
                bestName = candidate
            }
        }
        return bestName?.let { nameToId[it] }
    }

    private fun resolveRemoteLogoExact(channelName: String, tvgId: String?): String? {
        overridesByName[channelName]?.let { return it }
        overridesByName[stripCategorySuffix(channelName)]?.let { return it }

        for (variant in tvgIdVariants(tvgId)) {
            byChannelId[variant]?.let { return it }
        }

        val norm = TvgIdNormalizer.normalizeChannelName(channelName)
        LogoNameAliases.lookup(channelName, norm)?.let { aliasId ->
            byChannelId[aliasId]?.let { return it }
        }

        nameToId[norm]?.let { cid ->
            byChannelId[cid]?.let { return it }
        }

        return null
    }

    private fun fuzzyMatch(norm: String): String? {
        if (norm.length < 4 || nameToId.isEmpty()) {
            return null
        }
        val bucketKey = norm.take(3)
        val candidates = fuzzyBuckets[bucketKey] ?: nameToId.keys.toList()
        var bestName: String? = null
        var bestScore = FUZZY_CUTOFF
        for (candidate in candidates) {
            if (candidate.length < 4) continue
            val score = similarityRatio(norm, candidate)
            if (score >= bestScore) {
                bestScore = score
                bestName = candidate
            }
        }
        return bestName?.let { nameToId[it] }
    }

    private fun similarityRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        val dist = levenshtein(a, b)
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - dist.toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
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
        val compact = TvgIdNormalizer.compact(TvgIdNormalizer.normTvgId(tvgId))
        compactToIds[compact]?.forEach { variants += it }
        val dotted = tvgId.lowercase(Locale.US).replace(".", "")
        dottedToIds[dotted]?.forEach { variants += it }
        return variants.toList()
    }

    private fun loadAll() {
        loadLogosCsv()
        loadChannelsCsv()
        loadOverrides()
        loadRuntimeOverrides(appContext)
        rebuildIndexes()
    }

    private fun loadLogosCsv() {
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
            best.forEach { (channelId, triple) -> byChannelId[channelId] = triple.third }
        }
    }

    private fun loadChannelsCsv() {
        val local = mutableMapOf<String, String>()
        appContext.assets.open("channels_db_cache.csv").bufferedReader().use { reader ->
            reader.readLine()
            reader.forEachLine { line ->
                val row = parseCsvLine(line, listOf("id", "name", "alt_names", "network", "owners", "country", "categories"))
                val name = row["name"].orEmpty().trim()
                val channelId = row["id"].orEmpty().trim()
                if (name.isEmpty() || channelId.isEmpty()) {
                    return@forEachLine
                }
                val norm = TvgIdNormalizer.normalizeChannelName(name)
                if (norm.isNotEmpty() && norm !in local) {
                    local[norm] = channelId
                }
                row["alt_names"].orEmpty().split(';').forEach { alt ->
                    val altNorm = TvgIdNormalizer.normalizeChannelName(alt)
                    if (altNorm.isNotEmpty() && altNorm !in local) {
                        local[altNorm] = channelId
                    }
                }
            }
        }
        synchronized(nameToId) {
            nameToId.clear()
            nameToId.putAll(local)
        }
    }

    private fun loadOverrides() {
        try {
            val text = appContext.assets.open("channel_logo_overrides.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            synchronized(overridesByName) {
                overridesByName.clear()
                root.keys().forEach { key ->
                    val url = root.optString(key).trim()
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        overridesByName[key] = url
                    }
                }
            }
        } catch (_: Exception) {
            // Optional asset.
        }
    }

    private fun rebuildIndexes() {
        synchronized(this) {
            compactToIds.clear()
            dottedToIds.clear()
            fuzzyBuckets.clear()
            byChannelId.keys.forEach { channelId ->
                val dotted = channelId.lowercase(Locale.US).replace(".", "")
                dottedToIds.getOrPut(dotted) { mutableListOf() }.add(channelId)
                val compact = TvgIdNormalizer.compact(TvgIdNormalizer.normTvgId(channelId))
                if (compact.isNotEmpty()) {
                    compactToIds.getOrPut(compact) { mutableListOf() }.add(channelId)
                }
            }
            nameToId.keys.forEach { norm ->
                val bucket = norm.take(3)
                fuzzyBuckets.getOrPut(bucket) { mutableListOf() }.add(norm)
            }
        }
    }

    private fun stripCategorySuffix(name: String): String =
        name.replace(Regex(" \\[[^\\]]+\\]$"), "")

    private fun parseCsvLine(line: String, keys: List<String> = LOGO_CSV_KEYS): Map<String, String> {
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
        return keys.mapIndexedNotNull { index, key ->
            cols.getOrNull(index)?.let { key to it.trim('"') }
        }.toMap()
    }

    data class LogoStats(
        val logoEntries: Int,
        val nameEntries: Int,
        val overrides: Int,
        val loaded: Boolean,
    )

    companion object {
        private const val TAG = "LogoResolver"
        private const val FUZZY_CUTOFF = 0.85
        private const val TOKEN_FUZZY_CUTOFF = 0.72
        private val LOGO_CSV_KEYS = listOf("channel", "feed", "in_use", "tags", "width", "height", "format", "url")
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
