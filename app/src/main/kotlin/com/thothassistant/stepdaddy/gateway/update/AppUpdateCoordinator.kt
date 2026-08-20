package com.thothassistant.stepdaddy.gateway.update

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Process-wide update orchestration: one check chain, no overlapping dialogs, startup grace.
 */
class AppUpdateCoordinator(
    context: Context,
    private val environment: GatewayEnvironment,
) {
    private val appContext = context.applicationContext
    private val manager = AppUpdateManager(appContext, environment, ApkInstallManager(appContext))
    private val downloadMutex = Mutex()

    private var startupJob: Job? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    @Volatile
    private var availableUpdate: AppUpdateInfo? = null

    @Volatile
    private var autoCheckCompleted = false

    @Volatile
    private var promptedVersionCode = -1

    @Volatile
    private var installPromptVersionCode = -1

    @Volatile
    private var deferPromptsUntilMs = System.currentTimeMillis() + STARTUP_GRACE_MS

    private var primaryHostRef: WeakReference<AppCompatActivity>? = null
    private var activeDialogRef: WeakReference<AlertDialog>? = null
    private val availabilityListeners = CopyOnWriteArrayList<(AppUpdateInfo?) -> Unit>()

    private var pendingUpdatePrompt: AppUpdateInfo? = null
    private var pendingInstallPrompt: Pair<AppUpdateInfo, File>? = null

    fun addAvailabilityListener(listener: (AppUpdateInfo?) -> Unit) {
        if (!availabilityListeners.contains(listener)) {
            availabilityListeners.add(listener)
        }
        listener(availableUpdate)
    }

    fun removeAvailabilityListener(listener: (AppUpdateInfo?) -> Unit) {
        availabilityListeners.remove(listener)
    }

    fun setPrimaryHost(host: AppCompatActivity?) {
        primaryHostRef = host?.let { WeakReference(it) }
    }

    fun isAutoCheckCompleted(): Boolean = autoCheckCompleted

    fun manager(): AppUpdateManager = manager

    fun currentUpdate(): AppUpdateInfo? = availableUpdate

    /** Push back auto prompts (e.g. server start, ready banner). */
    fun deferPrompts(additionalMs: Long = READY_BANNER_GRACE_MS) {
        val until = System.currentTimeMillis() + additionalMs
        if (until > deferPromptsUntilMs) {
            deferPromptsUntilMs = until
        }
    }

    fun scheduleStartupFlow(host: AppCompatActivity) {
        if (startupJob?.isActive == true) return
        startupJob = host.lifecycleScope.launch {
            awaitPromptWindow()
            manager.pendingApkFile()
            maybeToastStalePending(host)
            if (promptPendingInstallIfNeeded(host, manual = false)) return@launch
            if (environment.autoCheckUpdates && !autoCheckCompleted) {
                runCheck(host, manual = false)
            }
        }
    }

    /** Call when the dashboard resumes so queued auto prompts can appear. */
    fun flushPendingPrompts(host: AppCompatActivity) {
        host.lifecycleScope.launch {
            awaitPromptWindow()
            pendingInstallPrompt?.let { (info, apk) ->
                pendingInstallPrompt = null
                showInstallReadyDialog(host, info, apk, manual = false)
                return@launch
            }
            pendingUpdatePrompt?.let { info ->
                pendingUpdatePrompt = null
                showUpdateDialog(host, info, manual = false)
            }
        }
    }

    fun checkForUpdate(host: AppCompatActivity, manual: Boolean) {
        host.lifecycleScope.launch {
            if (manual) {
                runCheck(host, manual = true)
            } else if (!autoCheckCompleted) {
                awaitPromptWindow()
                runCheck(host, manual = false)
            }
        }
    }

    fun downloadAndInstall(
        host: AppCompatActivity,
        info: AppUpdateInfo,
        showProgressToast: Boolean,
    ) {
        if (downloadJob?.isActive == true) return
        downloadJob = host.lifecycleScope.launch {
            if (showProgressToast) {
                toast(host, host.getString(R.string.update_downloading))
            }
            val result = downloadMutex.withLock {
                withContext(Dispatchers.IO) { manager.downloadUpdate(info) { } }
            }
            result.onSuccess { apk ->
                dismissActiveDialog()
                showInstallReadyDialog(host, info, apk, manual = true)
            }.onFailure { exc ->
                toastUpdateFailure(host, exc)
            }
        }
    }

    /**
     * Debug bridge → newly signed release package.
     * Cannot convert applicationIds in-place; user must install release (side-by-side or after uninstall).
     */
    fun graduateToRelease(host: AppCompatActivity) {
        if (!manager.isDebugBuild()) {
            toast(host, host.getString(R.string.graduate_release_debug_only))
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = host.lifecycleScope.launch {
            toast(host, host.getString(R.string.graduate_release_fetching))
            val fetch = withContext(Dispatchers.IO) { manager.fetchLatestManifest() }
            val info = fetch.getOrElse { exc ->
                toast(host, host.getString(R.string.graduate_release_failed, exc.message ?: "error"))
                return@launch
            }
            if (info == null || info.manifest.releaseApkUrl().isBlank()) {
                toast(host, host.getString(R.string.graduate_release_no_url))
                return@launch
            }
            dismissActiveDialog()
            val proceed = withContext(Dispatchers.Main) {
                showGraduateConfirmDialog(host, info)
            }
            if (!proceed) return@launch
            toast(host, host.getString(R.string.graduate_release_downloading))
            val result = downloadMutex.withLock {
                withContext(Dispatchers.IO) { manager.downloadReleaseForGraduation(info) { } }
            }
            result.onSuccess { apk ->
                dismissActiveDialog()
                showGraduateInstallDialog(host, info, apk)
            }.onFailure { exc ->
                toastUpdateFailure(host, exc)
            }
        }
    }

    private suspend fun showGraduateConfirmDialog(
        host: AppCompatActivity,
        info: AppUpdateInfo,
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (!host.isValidHost()) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.graduate_release_title)
            .setMessage(
                host.getString(
                    R.string.graduate_release_message,
                    info.manifest.versionName,
                    AppUpdateManager.RELEASE_PACKAGE,
                    AppUpdateManager.DEBUG_PACKAGE,
                ),
            )
            .setPositiveButton(R.string.graduate_release_download) { _, _ ->
                if (cont.isActive) cont.resume(true)
            }
            .setNegativeButton(R.string.update_action_later) { _, _ ->
                if (cont.isActive) cont.resume(false)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(false)
            }
            .create()
        activeDialogRef = WeakReference(dialog)
        dialog.show()
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
    }

    private fun showGraduateInstallDialog(
        host: AppCompatActivity,
        info: AppUpdateInfo,
        apkFile: File,
    ) {
        if (!host.isValidHost()) return
        dismissActiveDialog()
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.graduate_release_install_title)
            .setMessage(
                host.getString(
                    R.string.graduate_release_install_message,
                    info.manifest.versionName,
                    AppUpdateManager.DEBUG_PACKAGE,
                    AppUpdateManager.RELEASE_PACKAGE,
                ),
            )
            .setPositiveButton(R.string.update_action_install) { _, _ ->
                if (!manager.canInstallPackages()) {
                    toast(host, host.getString(R.string.install_apps_unknown_sources_hint))
                    manager.openInstallPermissionSettings()
                    return@setPositiveButton
                }
                if (manager.launchInstall(apkFile)) {
                    toast(host, host.getString(R.string.graduate_release_install_launched))
                } else {
                    toast(host, host.getString(R.string.install_apps_launch_failed))
                }
            }
            .setNegativeButton(R.string.update_action_later, null)
            .create()
        activeDialogRef = WeakReference(dialog)
        dialog.show()
    }

    private fun toastUpdateFailure(host: AppCompatActivity, exc: Throwable) {
        val message = exc.message.orEmpty()
        if (message.contains("Signing certificate mismatch", ignoreCase = true)) {
            toast(host, host.getString(R.string.update_signature_mismatch))
        } else {
            toast(host, host.getString(R.string.update_download_failed, message.ifBlank { "error" }))
        }
    }

    private suspend fun runCheck(host: AppCompatActivity, manual: Boolean) {
        if (checkJob?.isActive == true) {
            if (manual) toast(host, host.getString(R.string.update_checking))
            checkJob?.join()
            if (manual && availableUpdate != null) {
                showUpdateDialog(host, availableUpdate!!, manual = true)
            }
            return
        }
        checkJob = host.lifecycleScope.launch {
            if (manual) toast(host, host.getString(R.string.update_checking))
            val result = withContext(Dispatchers.IO) { manager.checkForUpdate() }
            result.onSuccess { info ->
                notifyAvailability(info)
                autoCheckCompleted = true
                if (info == null) {
                    if (manual) toast(host, host.getString(R.string.update_none))
                    return@onSuccess
                }
                if (environment.autoDownloadUpdates) {
                    handleAutoDownload(host, info)
                    return@onSuccess
                }
                if (!manual && !manager.shouldPrompt(info)) return@onSuccess
                if (!manual && promptedVersionCode >= info.manifest.versionCode) return@onSuccess
                awaitPromptWindow()
                showUpdateDialog(host, info, manual)
            }.onFailure { exc ->
                if (manual) {
                    toast(host, host.getString(R.string.update_check_failed, exc.message ?: "error"))
                }
            }
        }
        checkJob?.join()
    }

    private suspend fun handleAutoDownload(host: AppCompatActivity, info: AppUpdateInfo) {
        val pending = manager.pendingApkFile()
        maybeToastStalePending(host)
        if (pending != null && pendingVersionMatches(pending, info)) {
            awaitPromptWindow()
            showInstallReadyDialog(host, info, pending, manual = false)
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = host.lifecycleScope.launch {
            val result = downloadMutex.withLock {
                withContext(Dispatchers.IO) { manager.downloadUpdate(info) { } }
            }
            result.onSuccess { apk ->
                dismissActiveDialog()
                awaitPromptWindow()
                showInstallReadyDialog(host, info, apk, manual = false)
            }.onFailure { exc ->
                if (host.isValidHost()) toastUpdateFailure(host, exc)
            }
        }
    }

    private suspend fun promptPendingInstallIfNeeded(host: AppCompatActivity, manual: Boolean): Boolean {
        val apk = manager.pendingApkFile()
        maybeToastStalePending(host)
        if (apk == null) return false
        val info = availableUpdate ?: inferInfoFromApk(apk) ?: return false
        if (!manual && installPromptVersionCode >= info.manifest.versionCode) return false
        awaitPromptWindow()
        showInstallReadyDialog(host, info, apk, manual)
        return true
    }

    private fun inferInfoFromApk(apk: File): AppUpdateInfo? {
        val packageInfo = ApkInstallManager(appContext).resolvePackageInfo(apk) ?: return null
        val versionCode = packageInfo.longVersionCode.toInt()
        return AppUpdateInfo(
            manifest = UpdateManifest(
                versionCode = versionCode,
                versionName = packageInfo.versionName ?: versionCode.toString(),
                apkUrl = apk.absolutePath,
            ),
            sourceLabel = "pending",
        )
    }

    private fun pendingVersionMatches(apk: File, info: AppUpdateInfo): Boolean {
        val packageInfo = ApkInstallManager(appContext).resolvePackageInfo(apk) ?: return false
        return packageInfo.longVersionCode.toInt() == info.manifest.versionCode
    }

    private suspend fun awaitPromptWindow() {
        val waitMs = deferPromptsUntilMs - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
    }

    private fun showUpdateDialog(host: AppCompatActivity, info: AppUpdateInfo, manual: Boolean) {
        val dialogHost = resolveDialogHost(host, manual) ?: run {
            if (!manual) pendingUpdatePrompt = info
            return
        }
        if (!manual && promptedVersionCode >= info.manifest.versionCode) return
        dismissActiveDialog()
        val mandatory = manager.isMandatory(info)
        val dialog = AppUpdateDialogHelper.buildUpdateDialog(
            activity = dialogHost,
            info = info,
            mandatory = mandatory,
            onUpdate = {
                promptedVersionCode = info.manifest.versionCode
                downloadAndInstall(dialogHost, info, showProgressToast = true)
            },
            onDismiss = {
                activeDialogRef = null
                if (!mandatory) {
                    manager.dismissUpdate(info)
                    promptedVersionCode = info.manifest.versionCode
                }
            },
        )
        activeDialogRef = WeakReference(dialog)
        dialog.show()
        if (!manual) promptedVersionCode = info.manifest.versionCode
    }

    private fun showInstallReadyDialog(
        host: AppCompatActivity,
        info: AppUpdateInfo,
        apkFile: File,
        manual: Boolean,
    ) {
        val dialogHost = resolveDialogHost(host, manual) ?: run {
            if (!manual) pendingInstallPrompt = info to apkFile
            return
        }
        if (!manual && installPromptVersionCode >= info.manifest.versionCode) return
        dismissActiveDialog()
        val dialog = AppUpdateDialogHelper.buildInstallReadyDialog(
            activity = dialogHost,
            info = info,
            onInstall = {
                installPromptVersionCode = info.manifest.versionCode
                if (!manager.canInstallPackages()) {
                    toast(dialogHost, dialogHost.getString(R.string.install_apps_unknown_sources_hint))
                    manager.openInstallPermissionSettings()
                    return@buildInstallReadyDialog
                }
                if (manager.launchInstall(apkFile)) {
                    toast(dialogHost, dialogHost.getString(R.string.update_download_ready))
                } else {
                    toast(dialogHost, dialogHost.getString(R.string.install_apps_launch_failed))
                }
            },
            onDismiss = {
                activeDialogRef = null
                installPromptVersionCode = info.manifest.versionCode
            },
        )
        activeDialogRef = WeakReference(dialog)
        dialog.show()
    }

    private fun resolveDialogHost(host: AppCompatActivity, manual: Boolean): AppCompatActivity? {
        if (manual) return host.takeIf { it.isValidHost() }
        val primary = primaryHostRef?.get()
        if (primary != null && primary.isValidHost()) return primary
        return host.takeIf { it.isValidHost() }
    }

    private fun notifyAvailability(info: AppUpdateInfo?) {
        availableUpdate = info
        availabilityListeners.forEach { listener ->
            runCatching { listener(info) }
        }
    }

    private fun dismissActiveDialog() {
        runCatching { activeDialogRef?.get()?.dismiss() }
        activeDialogRef = null
    }

    private fun toast(host: AppCompatActivity, message: String) {
        if (host.isValidHost()) {
            Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeToastStalePending(host: AppCompatActivity) {
        if (manager.consumeStalePendingDiscarded()) {
            toast(host, host.getString(R.string.update_stale_pending_discarded))
        }
    }

    private fun AppCompatActivity.isValidHost(): Boolean = !isFinishing && !isDestroyed

    companion object {
        private const val STARTUP_GRACE_MS = 12_000L
        private const val READY_BANNER_GRACE_MS = 8_000L
    }
}
