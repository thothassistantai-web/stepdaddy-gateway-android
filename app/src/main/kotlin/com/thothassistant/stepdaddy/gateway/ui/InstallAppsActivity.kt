package com.thothassistant.stepdaddy.gateway.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.install.ApkInstallManager
import com.thothassistant.stepdaddy.gateway.install.InstallAppEntry
import com.thothassistant.stepdaddy.gateway.install.InstallAppIconLoader
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
    private lateinit var iconLoader: InstallAppIconLoader
    private lateinit var adapter: InstallAppsAdapter
    private lateinit var recyclerApps: RecyclerView
    private lateinit var textSummary: TextView
    private lateinit var textEmpty: TextView
    private lateinit var buttonRefresh: MaterialButton
    private lateinit var buttonSelectAll: MaterialButton
    private lateinit var buttonBatchInstall: MaterialButton
    private lateinit var buttonBack: MaterialButton

    private val items = mutableListOf<InstallAppUiItem>()
    private var batchJob: Job? = null
    private val installMutex = Mutex()
    private var allSelected = false

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
        iconLoader = InstallAppIconLoader(this, installManager)

        recyclerApps = findViewById(R.id.recyclerApps)
        textSummary = findViewById(R.id.textSummary)
        textEmpty = findViewById(R.id.textEmpty)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        buttonSelectAll = findViewById(R.id.buttonSelectAll)
        buttonBatchInstall = findViewById(R.id.buttonBatchInstall)
        buttonBack = findViewById(R.id.buttonBack)

        adapter = InstallAppsAdapter(
            iconLoader = iconLoader,
            onInstallClick = { item -> installSingle(item) },
            onSelectionChanged = { item, selected -> updateSelection(item.entry.id, selected) },
            onRowFocus = { position -> ensureRowVisible(position) },
            onFocusRowRequest = { position -> focusListItem(position) },
            onFocusToolbarRequest = { buttonBack.requestFocus() },
        )
        adapter.toolbarDownTargetId = R.id.buttonBack
        recyclerApps.layoutManager = LinearLayoutManager(this)
        recyclerApps.adapter = adapter
        recyclerApps.setHasFixedSize(true)

        buttonRefresh.setOnClickListener { refreshCatalog() }
        buttonSelectAll.setOnClickListener { toggleSelectAll() }
        buttonBatchInstall.setOnClickListener { batchInstallSelected() }
        buttonBack.setOnClickListener { finish() }

        buttonBack.nextFocusDownId = View.NO_ID
        buttonBack.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && items.isNotEmpty()) {
                focusFirstListItem()
                true
            } else {
                false
            }
        }

        buttonRefresh.requestFocus()
        requestInstallPermissionIfNeeded()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledVersions()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusFirstListItem() {
        focusListItem(0)
    }

    private fun focusListItem(position: Int) {
        if (position !in items.indices) return
        recyclerApps.post {
            val holder = recyclerApps.findViewHolderForAdapterPosition(position)
            val target = holder?.itemView?.findViewById<View>(R.id.checkSelect)
                ?: holder?.itemView?.findViewById(R.id.cardRoot)
            if (target != null) {
                target.requestFocus()
            } else {
                recyclerApps.scrollToPosition(position)
                recyclerApps.post {
                    val retryHolder = recyclerApps.findViewHolderForAdapterPosition(position)
                    retryHolder?.itemView?.findViewById<View>(R.id.checkSelect)?.requestFocus()
                        ?: retryHolder?.itemView?.findViewById<View>(R.id.cardRoot)?.requestFocus()
                }
            }
        }
    }

    private fun ensureRowVisible(position: Int) {
        recyclerApps.post {
            recyclerApps.smoothScrollToPosition(position)
        }
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
                    replaceItems(catalog.apps)
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
                    replaceItems(catalog.apps)
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

    private fun replaceItems(entries: List<InstallAppEntry>) {
        val previousSelection = items.associate { it.entry.id to it.selected }
        items.clear()
        items.addAll(
            entries.map { entry ->
                InstallAppUiItem(
                    entry = entry,
                    installedVersion = entry.packageName?.let {
                        installManager.getInstalledVersion(it)
                    },
                    selected = previousSelection[entry.id] == true,
                )
            },
        )
        allSelected = items.isNotEmpty() && items.all { it.selected }
        updateSelectAllLabel()
        publishItems()
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

    private fun toggleSelectAll() {
        if (items.isEmpty()) return
        allSelected = !allSelected
        items.indices.forEach { index ->
            items[index] = items[index].copy(selected = allSelected)
        }
        updateSelectAllLabel()
        publishItems()
    }

    private fun updateSelectAllLabel() {
        buttonSelectAll.text = if (allSelected) {
            getString(R.string.install_apps_deselect_all)
        } else {
            getString(R.string.install_apps_select_all)
        }
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
        allSelected = items.isNotEmpty() && items.all { it.selected }
        updateSelectAllLabel()
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
        buttonSelectAll.isEnabled = items.isNotEmpty() && batchJob?.isActive != true
    }

    private fun setLoading(loading: Boolean) {
        buttonRefresh.isEnabled = !loading
        buttonSelectAll.isEnabled = !loading && items.isNotEmpty()
        buttonRefresh.text = if (loading) {
            getString(R.string.install_apps_refreshing)
        } else {
            getString(R.string.install_apps_refresh)
        }
    }

    private fun setBatchEnabled(enabled: Boolean) {
        buttonBatchInstall.isEnabled = enabled && items.any { it.selected }
        buttonSelectAll.isEnabled = enabled && items.isNotEmpty()
    }
}
