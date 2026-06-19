package com.thothassistant.stepdaddy.gateway.install

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.thothassistant.stepdaddy.gateway.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class InstallAppIconLoader(
    private val context: Context,
    private val installManager: ApkInstallManager,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val bitmapCache = LruCache<String, Bitmap>(64)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val loadJobs = mutableMapOf<ImageView, Job>()

    fun loadInto(imageView: ImageView, item: InstallAppUiItem) {
        loadJobs.remove(imageView)?.cancel()
        val cacheKey = iconCacheKey(item)
        bitmapCache.get(cacheKey)?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(placeholderDrawable(item.entry.name))
        val job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { resolveBitmap(item) }
            if (bitmap != null) {
                bitmapCache.put(cacheKey, bitmap)
                imageView.setImageBitmap(bitmap)
            }
        }
        loadJobs[imageView] = job
    }

    fun clear(imageView: ImageView) {
        loadJobs.remove(imageView)?.cancel()
        imageView.setImageDrawable(null)
    }

    private fun iconCacheKey(item: InstallAppUiItem): String {
        val entry = item.entry
        return buildString {
            append(entry.id)
            append('|')
            append(entry.iconUrl.orEmpty())
            append('|')
            append(entry.packageName.orEmpty())
            append('|')
            append(item.installedVersion.orEmpty())
        }
    }

    private fun resolveBitmap(item: InstallAppUiItem): Bitmap? {
        val entry = item.entry
        loadFromUrl(entry.iconUrl)?.let { return it }
        entry.packageName?.let { packageName ->
            loadInstalledIcon(packageName)?.let { return it }
        }
        val apkFile = runCatching { installManager.apkFileFor(entry) }.getOrNull()
        if (apkFile != null && apkFile.exists() && apkFile.length() > 0) {
            loadArchiveIcon(apkFile)?.let { return it }
        }
        loadFavicon(entry)?.let { return it }
        return letterAvatar(entry.name)
    }

    private fun loadFromUrl(url: String?): Bitmap? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("http", ignoreCase = true)) return null
        return runCatching {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private fun loadInstalledIcon(packageName: String): Bitmap? =
        runCatching {
            val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationIcon(
                    context.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationIcon(packageName)
            }
            drawableToBitmap(drawable)
        }.getOrNull()

    private fun loadArchiveIcon(apkFile: File): Bitmap? =
        runCatching {
            val archiveInfo = installManager.resolvePackageInfo(apkFile) ?: return null
            archiveInfo.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getApplicationIcon(appInfo)
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationIcon(appInfo)
                }
                drawableToBitmap(drawable)
            }
        }.getOrNull()

    private fun loadFavicon(entry: InstallAppEntry): Bitmap? {
        val domain = faviconDomain(entry) ?: return null
        val url = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        return loadFromUrl(url)
    }

    private fun faviconDomain(entry: InstallAppEntry): String? {
        entry.packageName?.let { pkg ->
            return when {
                pkg.contains("tivimate") || pkg == "ar.tvplayer.tv" -> "tivimate.com"
                pkg.contains("sparkle") -> "sparkle-tv.com"
                pkg.contains("localsend") -> "localsend.org"
                pkg.contains("stremio") -> "stremio.com"
                else -> null
            }
        }
        val normalized = entry.name.lowercase()
        return when {
            normalized.contains("tivimate") -> "tivimate.com"
            normalized.contains("sparkle") -> "sparkle-tv.com"
            normalized.contains("localsend") -> "localsend.org"
            normalized.contains("stremio") -> "stremio.com"
            normalized.contains("iptv pro") -> "play.google.com"
            normalized.contains("perfect player") -> "play.google.com"
            normalized.contains("ott navigator") -> "play.google.com"
            else -> Uri.parse(entry.apkUrl).host
        }
    }

    private fun letterAvatar(name: String): Bitmap {
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = letterColor(name)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 16f, 16f, background)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.on_background)
            textSize = size * 0.45f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        textPaint.getTextBounds(letter, 0, letter.length, bounds)
        val y = size / 2f - bounds.exactCenterY()
        canvas.drawText(letter, size / 2f, y, textPaint)
        return bitmap
    }

    private fun letterColor(name: String): Int {
        val palette = intArrayOf(
            0xFF1E3A5F.toInt(),
            0xFF1F4D3D.toInt(),
            0xFF4A2C4F.toInt(),
            0xFF3D2C4A.toInt(),
            0xFF2C3D4A.toInt(),
            0xFF4A3D2C.toInt(),
        )
        return palette[name.hashCode().and(Int.MAX_VALUE) % palette.size]
    }

    private fun placeholderDrawable(name: String): Drawable {
        val bitmap = letterAvatar(name)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(ICON_SIZE_PX)
        val height = drawable.intrinsicHeight.coerceAtLeast(ICON_SIZE_PX)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val USER_AGENT = "StepDaddyGateway/1.0"
        private const val ICON_SIZE_PX = 192

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}
