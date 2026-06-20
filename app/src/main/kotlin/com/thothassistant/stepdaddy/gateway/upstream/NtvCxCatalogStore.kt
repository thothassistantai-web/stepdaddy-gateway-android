package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persists the raw ntv.cx `/api/get-channels` JSON so supplement sync can recover
 * after slow-network timeouts on low-end devices.
 */
class NtvCxCatalogStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "supplement/ntv_catalog.json").also {
        it.parentFile?.mkdirs()
    }

    fun readRaw(): String? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText() }.getOrElse { exc ->
            Log.w(TAG, "read catalog cache failed", exc)
            null
        }
    }

    fun writeRaw(jsonText: String) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.part")
            tmp.writeText(jsonText)
            if (!tmp.renameTo(file)) {
                file.writeText(jsonText)
                tmp.delete()
            }
        }.onFailure { exc -> Log.w(TAG, "write catalog cache failed", exc) }
    }

    fun loadCatalog(): List<NtvCxCdnLiveResolver.CatalogChannel> {
        val raw = readRaw() ?: return loadBundledCatalog()
        val rows = NtvCxCdnLiveResolver.parseCatalogJson(raw)
        if (rows.isNotEmpty()) {
            Log.i(TAG, "loaded ${rows.size} ntv.cx catalog rows from disk cache")
            return rows
        }
        return loadBundledCatalog()
    }

    fun loadBundledCatalog(): List<NtvCxCdnLiveResolver.CatalogChannel> =
        runCatching {
            appContext.assets.open(BUNDLED_ASSET).bufferedReader().use { reader ->
                NtvCxCdnLiveResolver.parseCatalogJson(reader.readText())
            }
        }.getOrElse { exc ->
            Log.w(TAG, "bundled catalog unavailable", exc)
            emptyList()
        }.also { rows ->
            if (rows.isNotEmpty()) {
                Log.i(TAG, "loaded ${rows.size} ntv.cx catalog rows from bundled bootstrap")
            }
        }

    companion object {
        private const val TAG = "NtvCxCatalogStore"
        const val BUNDLED_ASSET = "ntv_cx_catalog_bootstrap.json"
    }
}
