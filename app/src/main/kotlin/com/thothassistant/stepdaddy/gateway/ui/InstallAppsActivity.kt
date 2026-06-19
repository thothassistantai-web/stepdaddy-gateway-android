package com.thothassistant.stepdaddy.gateway.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import com.thothassistant.stepdaddy.gateway.install.InstallAppState
import com.thothassistant.stepdaddy.gateway.install.InstallAppUiItem
import com.thothassistant.stepdaddy.gateway.install.InstallAppsAdapter
import com.thothassistant.stepdaddy.gateway.install.InstallAppsCatalogRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InstallAppsActivity : AppCompatActivity() {
    private lateinit var catalogRepository: InstallAppsCatalogRepository
    private lateinit var installManager: ApkInstallManager
    private lateinit var adapter: InstallAppsAdapter
    private lateinit var recyclerApps: RecyclerView
    private lateinit var textSummary: TextView
    private lateinit var textEmpty: TextView
    private lateinit var buttonRefresh: MaterialButton
    private lateinit var buttonBatchInstall: MaterialButton
    private lateinit var buttonBack: MaterialButton

    private val items = mutableListOf<InstallAppUiItem>()
    private var batchJob: Job? = null
    private val installMutex = Mutex()

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.install_apps_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install_apps)
        catalogRepository = InstallAppsCatalogRepository(this)
        installManager = ApkInstallManager(this)

        recyclerApps = findViewById(R.id.recyclerApps)
        textSummary = findViewById(R.id.textSummary)
        textEmpty = findViewById(R.id.textEmpty)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        buttonBatchInstall = findViewById(R.id.buttonBatchInstall)
        buttonBack = findViewById(R.id.buttonBack)

        adapter = InstallAppsAdapter(
            onInstallClick = { item -> installSingle(item) },
            onSelectionChanged = { item, selected -> updateSelection(item.entry.id, selected) },
        )
        recyclerApps.layoutManager = LinearLayoutManager(this)
        recyclerApps.adapter = adapter
        recyclerApps.setHasFixedSize(true)

        buttonRefresh.setOnClickListener { refreshCatalog() }
        buttonBatchInstall.setOnClickListener { batchInstallSelected() }
        buttonBack.setOnClickListener { finish() }
        buttonRefresh.requestFocus()

        requestInstallPermissionIfNeeded()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledVersions()
    }

    private fun requestInstallPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !installManager.canRequestPackageInstalls()
        ) {
            installPermissionLauncher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }
    }

    private fun ensureInstallAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !installManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(this, R.string.install_apps_unknown_sources_hint, Toast.LENGTH_LONG).show()
            installManager.openInstallPermissionSettings()
            return false
        }
        return true
    }

    private fun loadCatalog() {
        lifecycleScope.launch {
            setLoading(true)
            runCatching { catalogRepository.loadCatalog() }
                .onSuccess { catalog ->
                    items.clear()
                    items.addAll(
                        catalog.apps.map { entry ->
                            InstallAppUiItem(
                                entry = entry,
                                installedVersion = entry.packageName?.let {
                                    installManager.getInstalledVersion(it)
                                },
                            )
                        },
                    )
                    publishItems()
                    textSummary.text = getString(
                        R.string.install_apps_summary,
                        catalog.apps.size,
                    )
                }
                .onFailure { exc ->
                    Toast.makeText(
                        this@InstallAppsActivity,
                        getString(R.string.install_apps_load_failed, exc.message ?: "error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            setLoading(false)
        }
    }

    private fun refreshCatalog() {
        lifecycleScope.launch {
            setLoading(true)
            runCatching { catalogRepository.refreshCatalog() }
                .onSuccess { catalog ->
                    items.clear()
                    items.addAll(
                        catalog.apps.map { entry ->
                            InstallAppUiItem(
                                entry = entry,
                                installedVersion = entry.packageName?.let {
                                    installManager.getInstalledVersion(it)
                                },
                            )
                        },
                    )
                    publishItems()
                    textSummary.text = getString(
                        R.string.install_apps_summary_refreshed,
                        catalog.apps.size,
                    )
                    Toast.makeText(
                        this@InstallAppsActivity,
                        R.string.install_apps_refresh_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { exc ->
                    Toast.makeText(
                        this@InstallAppsActivity,
                        getString(R.string.install_apps_refresh_failed, exc.message ?: "error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            setLoading(false)
        }
    }

    private fun refreshInstalledVersions() {
        var changed = false
        items.forEachIndexed { index, item ->
            val pkg = item.entry.packageName ?: return@forEachIndexed
            val version = installManager.getInstalledVersion(pkg)
            if (version != item.installedVersion) {
                items[index] = item.copy(installedVersion = version)
                changed = true
            }
        }
        if (changed) publishItems()
    }

    private fun installSingle(item: InstallAppUiItem) {
        if (!ensureInstallAllowed()) return
        lifecycleScope.launch {
            installMutex.withLock {
                runCatching { downloadAndInstall(item.entry) }
                    .onFailure { exc ->
                        updateItem(item.entry.id) {
                            it.copy(
                                state = InstallAppState.FAILED,
                                statusText = exc.message ?: "Failed",
                            )
                        }
                        Toast.makeText(
                            this@InstallAppsActivity,
                            getString(R.string.install_apps_failed, item.entry.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        }
    }

    private fun batchInstallSelected() {
        if (!ensureInstallAllowed()) return
        val selected = items.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.install_apps_batch_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        batchJob?.cancel()
        batchJob = lifecycleScope.launch {
            setBatchEnabled(false)
            var success = 0
            selected.forEachIndexed { index, item ->
                updateItem(item.entry.id) {
                    it.copy(statusText = getString(R.string.install_apps_batch_progress, index + 1, selected.size))
                }
                runCatching {
                    installMutex.withLock { downloadAndInstall(item.entry) }
                }.onSuccess { success++ }
                    .onFailure { exc ->
                        updateItem(item.entry.id) {
                            it.copy(
                                state = InstallAppState.FAILED,
                                statusText = exc.message ?: "Failed",
                            )
                        }
                    }
            }
            Toast.makeText(
                this@InstallAppsActivity,
                getString(R.string.install_apps_batch_done, success, selected.size),
                Toast.LENGTH_LONG,
            ).show()
            setBatchEnabled(true)
        }
    }

    private suspend fun downloadAndInstall(entry: InstallAppEntry) {
        updateItem(entry.id) {
            it.copy(
                state = InstallAppState.DOWNLOADING,
                progressPercent = 0,
                statusText = getString(R.string.install_apps_status_downloading),
            )
        }
        val apkFile = installManager.downloadApk(entry) { percent ->
            updateItem(entry.id) { it.copy(progressPercent = percent) }
        }

        val archiveInfo = installManager.resolvePackageInfo(apkFile)
        val packageName = entry.packageName ?: archiveInfo?.packageName
        val installedVersion = packageName?.let { installManager.getInstalledVersion(it) }

        updateItem(entry.id) {
            it.copy(
                state = InstallAppState.INSTALLING,
                progressPercent = 100,
                statusText = getString(R.string.install_apps_status_installing),
                installedVersion = installedVersion,
                entry = if (packageName != null && it.entry.packageName == null) {
                    it.entry.copy(packageName = packageName)
                } else {
                    it.entry
                },
            )
        }

        if (!installManager.launchInstall(apkFile)) {
            error(getString(R.string.install_apps_launch_failed))
        }

        updateItem(entry.id) {
            it.copy(
                state = InstallAppState.DONE,
                statusText = getString(R.string.install_apps_status_confirm_install),
            )
        }
    }

    private fun updateSelection(id: String, selected: Boolean) {
        val index = items.indexOfFirst { it.entry.id == id }
        if (index < 0) return
        items[index] = items[index].copy(selected = selected)
        publishItems()
    }

    private fun updateItem(id: String, transform: (InstallAppUiItem) -> InstallAppUiItem) {
        val index = items.indexOfFirst { it.entry.id == id }
        if (index < 0) return
        items[index] = transform(items[index])
        publishItems()
    }

    private fun publishItems() {
        adapter.submitList(items.toList())
        textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerApps.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        buttonBatchInstall.isEnabled = items.any { it.selected } && batchJob?.isActive != true
    }

    private fun setLoading(loading: Boolean) {
        buttonRefresh.isEnabled = !loading
        buttonRefresh.text = if (loading) {
            getString(R.string.install_apps_refreshing)
        } else {
            getString(R.string.install_apps_refresh)
        }
    }

    private fun setBatchEnabled(enabled: Boolean) {
        buttonBatchInstall.isEnabled = enabled && items.any { it.selected }
    }
}
