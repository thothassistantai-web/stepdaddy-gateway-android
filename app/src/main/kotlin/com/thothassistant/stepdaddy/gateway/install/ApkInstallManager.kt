package com.thothassistant.stepdaddy.gateway.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.thothassistant.stepdaddy.gateway.upstream.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ApkInstallManager(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val downloadDir: File
        get() = File(context.cacheDir, "apk_downloads").also { it.mkdirs() }

    fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun getInstalledVersion(packageName: String): String? =
        runCatching {
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName
        }.getOrNull()

    fun resolvePackageInfo(apkFile: File): PackageInfo? =
        runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()

    suspend fun downloadApk(
        entry: InstallAppEntry,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = apkFileFor(entry)
        if (target.exists() && isValidApk(target)) {
            onProgress(100)
            return@withContext target
        }
        if (target.exists()) {
            target.delete()
        }

        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                downloadApkOnce(entry, target, onProgress)
                return@withContext target
            } catch (exc: Throwable) {
                lastError = exc
                Log.w(TAG, "Download attempt ${attempt + 1}/$MAX_DOWNLOAD_ATTEMPTS failed: ${exc.message}")
                if (target.exists()) {
                    target.delete()
                }
            }
        }
        error(lastError?.message ?: "Download failed after $MAX_DOWNLOAD_ATTEMPTS attempts")
    }

    private suspend fun downloadApkOnce(
        entry: InstallAppEntry,
        target: File,
        onProgress: (Int) -> Unit,
    ) {
        val request = Request.Builder()
            .url(entry.apkUrl)
            .header("User-Agent", "StepDaddyGateway/1.0")
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build()
        httpClient.executeAsync(request).use { response ->
            if (!response.isSuccessful) {
                error("Download failed: HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                error("Download failed: server returned HTML instead of APK")
            }
            val body = response.body ?: error("Empty download body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                        read = input.read(buffer)
                    }
                }
            }
            if (total > 0 && target.length() != total) {
                error("Incomplete download: expected $total bytes, got ${target.length()}")
            }
            if (!isValidApk(target)) {
                error("Downloaded file is not a valid APK")
            }
        }
        onProgress(100)
    }

    private fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(2)
                if (input.read(header) != 2) return false
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        }.getOrDefault(false)
    }

    fun launchInstall(apkFile: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { exc ->
            Log.e(TAG, "Install intent failed: ${exc.message}")
            false
        }
    }

    fun apkFileFor(entry: InstallAppEntry): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(entry.apkUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safeName = entry.name
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(40)
        return File(downloadDir, "${safeName}_$hash.apk")
    }

    companion object {
        private const val TAG = "ApkInstallManager"
        private const val MAX_DOWNLOAD_ATTEMPTS = 3

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
