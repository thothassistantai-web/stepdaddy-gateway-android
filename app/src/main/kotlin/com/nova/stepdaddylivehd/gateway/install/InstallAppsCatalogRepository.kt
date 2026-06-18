package com.nova.stepdaddylivehd.gateway.install

import android.content.Context
import com.nova.stepdaddylivehd.gateway.upstream.getText
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
                    description = "IPTV / streaming app from TV2024 catalog",
                    iconUrl = null,
                    apkUrl = rawUrl,
                    source = SOURCE_TV2024,
                    packageName = guessPackageName(slug),
                    version = null,
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

    companion object {
        const val SOURCE_TV2024 = "tv2024"
        const val SOURCE_DOCSQUIFFY = "docsquiffy"
        private const val BUNDLED_CATALOG_ASSET = "install_apps_catalog.json"
        private const val CATALOG_CACHE_FILE = "install_apps_catalog_cache.json"
        private const val TV2024_GITHUB_API =
            "https://api.github.com/repos/jk2024988/TV2024/contents/"
        private const val DOCSQUIFFY_API = "https://www.docsquiffy.com/api/downloads"
        private const val DOCSQUIFFY_BASE = "https://www.docsquiffy.com"
        private const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/jk2024988/TV2024/main/install_apps_catalog.json"
        private const val USER_AGENT = "StepDaddyGateway/1.0"

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
)
