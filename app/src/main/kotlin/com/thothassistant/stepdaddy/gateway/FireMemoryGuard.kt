package com.thothassistant.stepdaddy.gateway

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fire Stick (~900MB RAM) survival helpers: lower peak footprint, raise process importance,
 * and aggressively release caches under LMK pressure. No-ops on non-Fire devices.
 */
object FireMemoryGuard : ComponentCallbacks2 {
    private const val TAG = "FireMemoryGuard"

    private val installed = AtomicBoolean(false)
    private val overlayAttached = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var overlayView: View? = null

    @Volatile
    private var trimListener: (() -> Unit)? = null

    fun install(context: Context, onTrim: (() -> Unit)? = null) {
        if (!FireTvDevice.isFireTv(context)) return
        appContext = context.applicationContext
        trimListener = onTrim
        if (installed.compareAndSet(false, true)) {
            appContext?.registerComponentCallbacks(this)
            Log.i(TAG, "Installed Fire memory guard")
        }
        acquireWakeLock()
        attachPriorityOverlay()
    }

    fun uninstall() {
        val ctx = appContext ?: return
        if (installed.compareAndSet(true, false)) {
            runCatching { ctx.unregisterComponentCallbacks(this) }
        }
        releaseWakeLock()
        detachPriorityOverlay()
        trimListener = null
        appContext = null
    }

    /** Smaller OkHttp pools for Fire Stick — fewer idle sockets / threads. */
    fun compactHttpClient(
        connectSec: Long = 10,
        readSec: Long = 20,
        writeSec: Long = 20,
        callSec: Long = 30,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(2, 60, TimeUnit.SECONDS))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(writeSec, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .build()

    fun connectionPoolMaxIdle(): Int = 2

    /** Skip multi-MB logo / iptv-org CSV indexes on Fire — placeholders + disk mapper only. */
    fun skipHeavyCatalogIndexes(context: Context): Boolean = FireTvDevice.isFireTv(context)

    /** Defer playlist prewarm / EPG rebuild / logo enrich until steady-state. */
    fun deferHeavyBootWork(context: Context): Boolean = FireTvDevice.isFireTv(context)

    fun releaseCaches() {
        trimListener?.invoke()
        runCatching { Runtime.getRuntime().gc() }
        Log.i(TAG, "Released Fire caches (trim)")
    }

    private fun acquireWakeLock() {
        val ctx = appContext ?: return
        if (wakeLock?.isHeld == true) return
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stepdaddy:fire_gateway").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        Log.i(TAG, "Partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    /**
     * Persistent 1px overlay keeps the process associated with a visible window on Fire OS,
     * which tends to raise LMK priority above a bare FGS.
     */
    private fun attachPriorityOverlay() {
        val ctx = appContext ?: return
        if (overlayAttached.get()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; skip priority overlay")
            return
        }
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            if (overlayAttached.get()) return@post
            runCatching {
                val wm = ctx.getSystemService(WindowManager::class.java) ?: return@runCatching
                val view = View(ctx).apply {
                    setBackgroundColor(0x01000000)
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    width = 1
                    height = 1
                }
                wm.addView(view, params)
                overlayView = view
                overlayAttached.set(true)
                Log.i(TAG, "Priority overlay attached")
            }.onFailure { exc ->
                Log.w(TAG, "Priority overlay failed: ${exc.message}")
            }
        }
    }

    private fun detachPriorityOverlay() {
        val ctx = appContext ?: return
        val view = overlayView ?: return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            runCatching {
                ctx.getSystemService(WindowManager::class.java)?.removeView(view)
            }
            overlayView = null
            overlayAttached.set(false)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            Log.w(TAG, "onTrimMemory level=$level — releasing caches")
            releaseCaches()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        Log.w(TAG, "onLowMemory — releasing caches")
        releaseCaches()
    }
}
