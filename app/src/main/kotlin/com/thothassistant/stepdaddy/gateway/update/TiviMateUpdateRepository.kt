package com.thothassistant.stepdaddy.gateway.update

import com.thothassistant.stepdaddy.gateway.BuildConfig
import com.thothassistant.stepdaddy.gateway.upstream.getText
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class TiviMateUpdateRepository(
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetchLatestUpdate(): TiviMateUpdateInfo? {
        val releases = fetchReleases() ?: return null
        val candidates = releases
            .filter { release ->
                release.tagName.startsWith(TIVIMATE_TAG_PREFIX, ignoreCase = true)
            }
            .mapNotNull { release -> parseRelease(release) }
        return candidates.maxWithOrNull(compareBy({ it.manifest.versionCode }, { it.manifest.versionName }))
    }

    private suspend fun fetchReleases(): List<GitHubRelease>? {
        val repo = BuildConfig.TIVIMATE_GITHUB_RELEASE_REPO.trim()
        if (repo.isEmpty()) return null
        val url = "https://api.github.com/repos/$repo/releases?per_page=$RELEASES_PAGE_SIZE"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        return runCatching {
            val text = httpClient.getText(request)
            json.decodeFromString<List<GitHubRelease>>(text)
        }.getOrNull()
    }

    private suspend fun parseRelease(release: GitHubRelease): TiviMateUpdateInfo? {
        val tag = release.tagName.trim()
        if (tag.isEmpty()) return null
        val repo = BuildConfig.TIVIMATE_GITHUB_RELEASE_REPO.trim()
        val releasePageUrl = "https://github.com/$repo/releases/tag/${tag.urlEncode()}"
        val manifest = fetchManifestFromRelease(release)
            ?: buildManifestFromAssets(release)
            ?: return null
        if (manifest.apkUrl.isBlank()) return null
        return TiviMateUpdateInfo(
            manifest = manifest,
            releaseTag = tag,
            releasePageUrl = releasePageUrl,
            sourceLabel = "github",
        )
    }

    private suspend fun fetchManifestFromRelease(release: GitHubRelease): TiviMateUpdateManifest? {
        val manifestAsset = release.assets.firstOrNull {
            it.name.equals(MANIFEST_ASSET_NAME, ignoreCase = true)
        } ?: return null
        val request = Request.Builder()
            .url(manifestAsset.browserDownloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        val text = runCatching { httpClient.getText(request) }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            json.decodeFromString(TiviMateUpdateManifest.serializer(), text)
        }.getOrNull()
    }

    private fun buildManifestFromAssets(release: GitHubRelease): TiviMateUpdateManifest? {
        val apkAsset = release.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                !asset.name.contains("debug", ignoreCase = true)
        } ?: return null
        val versionName = release.tagName
            .removePrefix(TIVIMATE_TAG_PREFIX)
            .removePrefix("v")
            .ifBlank { release.name.orEmpty() }
        val versionCode = parseVersionCodeFromBody(release.body)
            ?: PatchVersionComparator.versionCodeFromPatchName(versionName)
            ?: return null
        return TiviMateUpdateManifest(
            versionCode = versionCode,
            versionName = versionName.ifBlank { versionCode.toString() },
            apkUrl = apkAsset.browserDownloadUrl,
            apkFileName = apkAsset.name,
            releaseNotes = release.body?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseVersionCodeFromBody(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        return VERSION_CODE_BODY_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val MANIFEST_ASSET_NAME = "update-manifest.json"
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val RELEASES_PAGE_SIZE = 30
        private val VERSION_CODE_BODY_REGEX =
            Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)

        val TIVIMATE_TAG_PREFIX: String
            get() = BuildConfig.TIVIMATE_RELEASE_TAG_PREFIX

        fun releasesPageUrl(): String {
            val repo = BuildConfig.TIVIMATE_GITHUB_RELEASE_REPO.trim()
            return "https://github.com/$repo/releases"
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
