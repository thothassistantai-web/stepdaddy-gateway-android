package com.thothassistant.stepdaddy.gateway.install

import android.content.Context
import com.thothassistant.stepdaddy.gateway.upstream.getText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class InstallAppsCatalogRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cachedCatalogFile: File
        get() = File(context.filesDir, CATALOG_CACHE_FILE)

    suspend fun loadCatalog(): InstallAppsCatalog {
        val cached = readCachedCatalog()
        if (cached != null && cached.apps.isNotEmpty()) {
            return cached
        }
        return readBundledCatalog()
    }

    suspend fun refreshCatalog(): InstallAppsCatalog {
        val remote = runCatching { fetchRemoteCatalog() }.getOrNull()
        if (remote != null && remote.apps.isNotEmpty()) {
            writeCachedCatalog(remote)
            return remote
        }

        val merged = mergeCatalogs(
            fetchTv2024Catalog(),
            fetchDocSquiffyCatalog(),
        )
        if (merged.apps.isNotEmpty()) {
            writeCachedCatalog(merged)
        }
        return merged
    }

    private fun readBundledCatalog(): InstallAppsCatalog {
        context.assets.open(BUNDLED_CATALOG_ASSET).use { stream ->
            val text = stream.bufferedReader().readText()
            return json.decodeFromString(InstallAppsCatalog.serializer(), text)
        }
    }

    private fun readCachedCatalog(): InstallAppsCatalog? =
        runCatching {
            if (!cachedCatalogFile.exists()) return null
            val text = cachedCatalogFile.readText()
            json.decodeFromString(InstallAppsCatalog.serializer(), text)
        }.getOrNull()

    private fun writeCachedCatalog(catalog: InstallAppsCatalog) {
        cachedCatalogFile.parentFile?.mkdirs()
        cachedCatalogFile.writeText(json.encodeToString(InstallAppsCatalog.serializer(), catalog))
    }

    private suspend fun fetchRemoteCatalog(): InstallAppsCatalog? {
        val request = Request.Builder()
            .url(REMOTE_CATALOG_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        val text = httpClient.getText(request)
        val catalog = json.decodeFromString(InstallAppsCatalog.serializer(), text)
        return catalog.takeIf { it.apps.isNotEmpty() }
    }

    private suspend fun fetchTv2024Catalog(): List<InstallAppEntry> {
        val request = Request.Builder()
            .url(TV2024_GITHUB_API)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        val text = httpClient.getText(request)
        val entries = json.decodeFromString<List<GitHubContentEntry>>(text)
        return entries
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .map { entry ->
                val slug = entry.name.removeSuffix(".apk")
                val rawUrl = entry.downloadUrl
                    ?: "https://raw.githubusercontent.com/jk2024988/TV2024/main/" +
                    URLEncoder.encode(entry.name, StandardCharsets.UTF_8.toString())
                        .replace("+", "%20")
                InstallAppEntry(
                    id = "tv2024-${slug.replace(" ", "_").take(60)}",
                    name = slug,
                    description = describeTv2024App(slug),
                    iconUrl = iconUrlForSlug(slug),
                    apkUrl = rawUrl,
                    source = SOURCE_TV2024,
                    packageName = guessPackageName(slug),
                    version = parseVersionFromName(slug),
                    fileSizeBytes = entry.size,
                )
            }
    }

    private suspend fun fetchDocSquiffyCatalog(): List<InstallAppEntry> {
        val request = Request.Builder()
            .url(DOCSQUIFFY_API)
            .header("User-Agent", USER_AGENT)
            .build()
        val text = httpClient.getText(request)
        val downloads = json.decodeFromString<List<DocSquiffyDownload>>(text)
        return downloads.mapNotNull { item ->
            if (item.status != null && item.status != "active") return@mapNotNull null
            val apkUrl = resolveDocSquiffyApkUrl(item) ?: return@mapNotNull null
            val iconUrl = item.iconUrl?.let { url ->
                if (url.startsWith("http")) url else "$DOCSQUIFFY_BASE$url"
            }
            InstallAppEntry(
                id = "docsquiffy-${item.id}",
                name = item.title ?: item.fileName ?: "App ${item.id}",
                description = item.description.orEmpty().take(200),
                iconUrl = iconUrl,
                apkUrl = apkUrl,
                source = SOURCE_DOCSQUIFFY,
                packageName = guessPackageName(item.title ?: item.fileName.orEmpty()),
                version = item.version?.takeIf { it.isNotBlank() },
                fileSizeBytes = item.fileSize,
            )
        }
    }

    private fun resolveDocSquiffyApkUrl(item: DocSquiffyDownload): String? {
        val external = item.externalUrl?.trim().orEmpty()
        if (external.endsWith(".apk", ignoreCase = true)) return external
        val fileUrl = item.fileUrl?.trim().orEmpty()
        if (fileUrl.startsWith("http") && fileUrl.contains(".apk", ignoreCase = true)) return fileUrl
        if (fileUrl.startsWith("/")) return "$DOCSQUIFFY_BASE$fileUrl"
        return null
    }

    private fun mergeCatalogs(
        tv2024: List<InstallAppEntry>,
        docsquiffy: List<InstallAppEntry>,
    ): InstallAppsCatalog {
        val merged = linkedMapOf<String, InstallAppEntry>()
        (tv2024 + docsquiffy).forEach { entry ->
            merged.putIfAbsent(entry.id, entry)
        }
        return InstallAppsCatalog(
            version = 1,
            apps = merged.values.sortedWith(
                compareBy<InstallAppEntry> { it.source }.thenBy { it.name.lowercase() },
            ),
        )
    }

    private fun guessPackageName(label: String): String? {
        val normalized = label.lowercase()
        return KNOWN_PACKAGES.entries.firstOrNull { (key, _) ->
            normalized.contains(key)
        }?.value
    }

    private fun parseVersionFromName(name: String): String? {
        val match = VERSION_IN_NAME.find(name) ?: return null
        return match.value.trim('.')
    }

    private fun describeTv2024App(slug: String): String {
        val normalized = slug.lowercase()
        return when {
            normalized.contains("tivimate") ->
                "Premium IPTV player with EPG, recordings, and catch-up support."
            normalized.contains("sparkle") ->
                "Lightweight IPTV player for live TV playlists and EPG."
            normalized.contains("iptv pro") ->
                "Feature-rich IPTV client for M3U playlists and multicast streams."
            normalized.contains("ott navigator") ->
                "Advanced IPTV navigator with provider and playlist management."
            normalized.contains("perfect player") ->
                "Minimal IPTV player focused on performance and UDP/multicast."
            normalized.contains("localsend") ->
                "Share files across devices on your local network without cloud upload."
            normalized.contains("send files to tv") ->
                "Send photos, videos, and APKs from phone to Android TV."
            normalized.contains("apktool") ->
                "Reverse-engineering utility for decoding and rebuilding APK files."
            else -> "Streaming or utility app from the TV2024 community catalog."
        }
    }

    private fun iconUrlForSlug(slug: String): String? {
        val normalized = slug.lowercase()
        val domain = when {
            normalized.contains("tivimate") -> "tivimate.com"
            normalized.contains("sparkle") -> "sparkle-tv.com"
            normalized.contains("localsend") -> "localsend.org"
            normalized.contains("stremio") -> "stremio.com"
            normalized.contains("iptv pro") -> "play.google.com"
            normalized.contains("perfect player") -> "play.google.com"
            normalized.contains("ott navigator") -> "play.google.com"
            else -> return null
        }
        return "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    }

    fun findBestTiviMateEntry(catalog: InstallAppsCatalog): InstallAppEntry? {
        val candidates = catalog.apps.filter { entry ->
            TIVIMATE_NAME.containsMatchIn(entry.name)
        }
        if (candidates.isEmpty()) return null

        val premium = candidates.filter { it.name.contains("premium", ignoreCase = true) }
        val tv2024Premium = premium.filter { it.source == SOURCE_TV2024 }
        val pool = when {
            tv2024Premium.isNotEmpty() -> tv2024Premium
            premium.isNotEmpty() -> premium
            else -> {
                val tv2024 = candidates.filter { it.source == SOURCE_TV2024 }
                if (tv2024.isNotEmpty()) tv2024 else candidates
            }
        }

        return pool.maxWithOrNull(tivimateEntryComparator)
    }

    /** StepDaddy-patched TiViMate (bidirectional control). */
    fun findStepDaddyTiviMateEntry(catalog: InstallAppsCatalog): InstallAppEntry? {
        catalog.apps.firstOrNull { entry ->
            entry.id.equals(STEPDADDY_TIVIMATE_CATALOG_ID, ignoreCase = true) ||
                entry.name.contains("stepdaddy", ignoreCase = true) ||
                (entry.name.contains("daddy", ignoreCase = true) &&
                    TIVIMATE_NAME.containsMatchIn(entry.name))
        }?.takeIf { it.apkUrl.isNotBlank() }?.let { return it }

        val overrideUrl = com.thothassistant.stepdaddy.gateway.BuildConfig
            .DEFAULT_TIVIMATE_STEPDADDY_APK_URL
            .trim()
        if (overrideUrl.isNotBlank()) {
            return InstallAppEntry(
                id = STEPDADDY_TIVIMATE_CATALOG_ID,
                name = "TiviMate Daddy (StepDaddy)",
                description = "4.6.1 mod + StepDaddy patch — bidirectional gateway control.",
                iconUrl = "https://www.google.com/s2/favicons?domain=tivimate.com&sz=128",
                apkUrl = overrideUrl,
                source = SOURCE_STEPDADDY,
                packageName = "ar.tvplayer.tv",
                version = "1.2.1-boot-tune-safe",
            )
        }
        return null
    }

    /** 4.6.1 premium mod base (no StepDaddy patch). */
    fun find461ModTiviMateEntry(catalog: InstallAppsCatalog): InstallAppEntry? {
        catalog.apps.firstOrNull { it.id == MOD_461_CATALOG_ID }?.let { return it }
        return catalog.apps.firstOrNull { entry ->
            TIVIMATE_NAME.containsMatchIn(entry.name) &&
                entry.name.contains("4.6.1", ignoreCase = true) &&
                entry.apkUrl.isNotBlank()
        }
    }

    companion object {
        const val SOURCE_TV2024 = "tv2024"
        const val SOURCE_DOCSQUIFFY = "docsquiffy"
        const val SOURCE_STEPDADDY = "stepdaddy"
        const val STEPDADDY_TIVIMATE_CATALOG_ID = "stepdaddy-TiviMate-4.6.1-StepDaddy"
        const val MOD_461_CATALOG_ID = "tv2024-TiviMate-v4.6.1-Premium-Mod"
        const val TIVIMATE_OFFICIAL_URL = "https://tivimate.com"
        private const val BUNDLED_CATALOG_ASSET = "install_apps_catalog.json"
        private const val CATALOG_CACHE_FILE = "install_apps_catalog_cache.json"
        private const val TV2024_GITHUB_API =
            "https://api.github.com/repos/jk2024988/TV2024/contents/"
        private const val DOCSQUIFFY_API = "https://www.docsquiffy.com/api/downloads"
        private const val DOCSQUIFFY_BASE = "https://www.docsquiffy.com"
        private const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/jk2024988/TV2024/main/install_apps_catalog.json"
        private const val USER_AGENT = "StepDaddyGateway/1.0"

        private val TIVIMATE_NAME = Regex("""tivimate""", RegexOption.IGNORE_CASE)
        private val TIVIMATE_VERSION = Regex("""(\d+(?:\.\d+)+)""")
        private val VERSION_IN_NAME = Regex("""v?(\d+(?:\.\d+)+)""", RegexOption.IGNORE_CASE)

        private val tivimateEntryComparator = Comparator<InstallAppEntry> { left, right ->
            val leftPremium = if (left.name.contains("premium", ignoreCase = true)) 1 else 0
            val rightPremium = if (right.name.contains("premium", ignoreCase = true)) 1 else 0
            if (leftPremium != rightPremium) return@Comparator leftPremium - rightPremium

            val leftTv2024 = if (left.source == SOURCE_TV2024) 1 else 0
            val rightTv2024 = if (right.source == SOURCE_TV2024) 1 else 0
            if (leftTv2024 != rightTv2024) return@Comparator leftTv2024 - rightTv2024

            compareVersionParts(
                parseTiviMateVersion(left.name),
                parseTiviMateVersion(right.name),
            )
        }

        private fun parseTiviMateVersion(name: String): List<Int> {
            val matches = TIVIMATE_VERSION.findAll(name).toList()
            val last = matches.lastOrNull()?.value ?: return listOf(0)
            return last.split('.').mapNotNull { part -> part.toIntOrNull() }
        }

        private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
            val maxLen = maxOf(left.size, right.size)
            for (index in 0 until maxLen) {
                val leftPart = left.getOrElse(index) { 0 }
                val rightPart = right.getOrElse(index) { 0 }
                if (leftPart != rightPart) return leftPart - rightPart
            }
            return 0
        }

        private val KNOWN_PACKAGES = mapOf(
            "tivimate" to "ar.tvplayer.tv",
            "sparkle" to "com.liskovsoft.sparkle",
            "iptv pro" to "ru.iptvremote.android.iptv.pro",
            "ott navigator" to "studio.scillarium.ottnavigator",
            "perfect player" to "com.nexstreaming.app.kinnypot",
            "localsend" to "org.localsend.localsend_app",
            "send files to tv" to "com.yablio.sendfilestotv",
            "beetv" to "com.beetv.app",
            "cinema hd" to "com.cinema.hd",
            "stremio" to "com.stremio.one",
        )

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

@Serializable
private data class GitHubContentEntry(
    val name: String,
    @SerialName("download_url") val downloadUrl: String? = null,
    val size: Long? = null,
)

@Serializable
private data class DocSquiffyDownload(
    val id: Int,
    val title: String? = null,
    val description: String? = null,
    val version: String? = null,
    val fileUrl: String? = null,
    val externalUrl: String? = null,
    val fileName: String? = null,
    val iconUrl: String? = null,
    val status: String? = null,
    val fileSize: Long? = null,
)
