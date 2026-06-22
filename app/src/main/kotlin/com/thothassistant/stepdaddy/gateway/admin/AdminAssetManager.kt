package com.thothassistant.stepdaddy.gateway.admin

import android.content.Context
import com.thothassistant.stepdaddy.gateway.epg.DaddyliveEpgResearchStore
import com.thothassistant.stepdaddy.gateway.epg.EpgChannelMapper
import com.thothassistant.stepdaddy.gateway.model.AdminImportResult
import com.thothassistant.stepdaddy.gateway.upstream.CategoryOverrideStore
import com.thothassistant.stepdaddy.gateway.upstream.LogoResolver
import org.json.JSONObject
import java.io.File

/** Read/write runtime asset overlays (bundled APK assets stay read-only). */
class AdminAssetManager(private val context: Context) {

    enum class AssetType(val label: String, val bundledAsset: String?) {
        EPG_NAME("epg-name-overrides", "epg_name_overrides.json"),
        LOGO("logo-overrides", "channel_logo_overrides.json"),
        EPG_ID("epg-id-map", "channel_epg_map.json"),
        EPG_RESEARCH("epg-research", "daddylive_epg_research.json"),
        CATEGORY("category-overrides", null),
    }

    fun export(type: AssetType, layer: String = "merged"): Map<String, String> = when (type) {
        AssetType.EPG_NAME -> exportEpgName(layer)
        AssetType.LOGO -> exportLogo(layer)
        AssetType.EPG_ID -> exportEpgId(layer)
        AssetType.EPG_RESEARCH -> exportEpgResearch(layer)
        AssetType.CATEGORY -> CategoryOverrideStore.snapshot()
    }

    fun importJson(
        type: AssetType,
        entries: Map<String, String>,
        merge: Boolean = true,
        logoResolver: com.thothassistant.stepdaddy.gateway.upstream.LogoResolver? = null,
    ): AdminImportResult {
        if (entries.isEmpty()) {
            return AdminImportResult(ok = false, imported = 0, message = "Empty payload")
        }
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        when (type) {
            AssetType.EPG_NAME -> {
                val mapper = EpgChannelMapper(context)
                entries.forEach { (name, tvgId) ->
                    if (name.isBlank() || tvgId.isBlank()) {
                        skipped++
                        return@forEach
                    }
                    mapper.putRuntimeNameOverride(name, tvgId)
                    imported++
                }
                mapper.saveRuntimeNameOverrides(context)
            }
            AssetType.LOGO -> {
                val resolver = logoResolver
                    ?: return AdminImportResult(ok = false, message = "Logo resolver unavailable")
                entries.forEach { (name, url) ->
                    if (name.isBlank() || !url.startsWith("http")) {
                        skipped++
                        return@forEach
                    }
                    resolver.putRuntimeOverride(name, url)
                    imported++
                }
                resolver.saveRuntimeOverrides(context)
            }
            AssetType.EPG_ID -> {
                val mapper = EpgChannelMapper(context)
                entries.forEach { (channelId, tvgId) ->
                    if (channelId.isBlank() || tvgId.isBlank()) {
                        skipped++
                        return@forEach
                    }
                    mapper.putRuntimeIdOverride(channelId, tvgId)
                    imported++
                }
                mapper.saveRuntimeIdMap(context)
            }
            AssetType.EPG_RESEARCH -> {
                val store = DaddyliveEpgResearchStore(context)
                entries.forEach { (channelId, tvgId) ->
                    if (channelId.isBlank() || tvgId.isBlank()) {
                        skipped++
                        return@forEach
                    }
                    store.putRuntimeMapping(
                        channelId = channelId,
                        tvgId = tvgId,
                        confidence = 1.0f,
                        method = "admin_import",
                    )
                    imported++
                }
                store.save()
            }
            AssetType.CATEGORY -> {
                entries.forEach { (key, group) ->
                    if (group !in CategoryOverrideStore.validGroups) {
                        errors += "Invalid group for $key: $group"
                        skipped++
                        return@forEach
                    }
                    when {
                        key.startsWith("id:") -> CategoryOverrideStore.put(
                            context,
                            channelId = key.removePrefix("id:"),
                            channelName = null,
                            groupTitle = group,
                        )
                        key.startsWith("name:") -> CategoryOverrideStore.put(
                            context,
                            channelId = null,
                            channelName = key.removePrefix("name:"),
                            groupTitle = group,
                        )
                        else -> CategoryOverrideStore.put(
                            context,
                            channelId = null,
                            channelName = key,
                            groupTitle = group,
                        )
                    }
                    imported++
                }
            }
        }
        return AdminImportResult(
            ok = errors.isEmpty(),
            imported = imported,
            skipped = skipped,
            errors = errors,
            message = "Imported $imported entries (${type.label})",
        )
    }

    fun importEpgCsv(csv: String, mapper: EpgChannelMapper): AdminImportResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        csv.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            if (index == 0 && trimmed.lowercase().contains("channel")) return@forEachIndexed
            val cols = parseCsvLine(trimmed)
            if (cols.size < 2) {
                skipped++
                return@forEachIndexed
            }
            val channelId = cols.getOrNull(0)?.trim().orEmpty()
            val channelName = cols.getOrNull(1)?.trim().orEmpty()
            val tvgId = cols.getOrNull(2)?.trim().orEmpty()
            if (tvgId.isEmpty()) {
                skipped++
                return@forEachIndexed
            }
            when {
                channelId.isNotEmpty() -> mapper.putRuntimeIdOverride(channelId, tvgId)
                channelName.isNotEmpty() -> mapper.putRuntimeNameOverride(channelName, tvgId)
                else -> {
                    errors += "Line ${index + 1}: need channel_id or channel_name"
                    skipped++
                    return@forEachIndexed
                }
            }
            imported++
        }
        if (imported > 0) {
            mapper.saveRuntimeNameOverrides(context)
            mapper.saveRuntimeIdMap(context)
        }
        return AdminImportResult(
            ok = errors.isEmpty(),
            imported = imported,
            skipped = skipped,
            errors = errors,
            message = "CSV import: $imported mappings",
        )
    }

    fun clearRuntime(type: AssetType) {
        when (type) {
            AssetType.EPG_NAME -> {
                File(context.filesDir, EpgChannelMapper.RUNTIME_NAME_OVERRIDES_FILE).delete()
            }
            AssetType.LOGO -> LogoResolver.runtimeOverridesFile(context).delete()
            AssetType.EPG_ID -> File(context.filesDir, "epg/channel_epg_map.json").delete()
            AssetType.EPG_RESEARCH -> DaddyliveEpgResearchStore.runtimeResearchFile(context).delete()
            AssetType.CATEGORY -> CategoryOverrideStore.clearRuntime(context)
        }
    }

    private fun exportEpgName(layer: String): Map<String, String> {
        val bundled = loadBundledJson(AssetType.EPG_NAME.bundledAsset!!)
        val runtime = loadRuntimeJson(EpgChannelMapper.runtimeNameOverridesFile(context))
        return when (layer.lowercase()) {
            "bundled" -> bundled
            "runtime" -> runtime
            else -> bundled + runtime
        }
    }

    private fun exportLogo(layer: String): Map<String, String> {
        val bundled = loadBundledJson(AssetType.LOGO.bundledAsset!!)
        val runtime = loadRuntimeJson(LogoResolver.runtimeOverridesFile(context))
        return when (layer.lowercase()) {
            "bundled" -> bundled
            "runtime" -> runtime
            else -> bundled + runtime
        }
    }

    private fun exportEpgResearch(layer: String): Map<String, String> {
        val bundled = loadBundledEpgResearch()
        val runtimeFile = DaddyliveEpgResearchStore.runtimeResearchFile(context)
        val runtime = if (runtimeFile.isFile) {
            loadEpgResearchMappings(runtimeFile)
        } else {
            emptyMap()
        }
        val merged = when (layer.lowercase()) {
            "bundled" -> bundled
            "runtime" -> runtime
            else -> bundled + runtime
        }
        return merged.mapValues { (_, entry) -> entry.tvgId }
    }

    private fun exportEpgId(layer: String): Map<String, String> {
        val bundled = loadBundledEpgIdMap()
        val runtimeFile = File(context.filesDir, "epg/channel_epg_map.json")
        val runtime = if (runtimeFile.isFile) {
            runCatching {
                val root = JSONObject(runtimeFile.readText())
                val mapping = root.optJSONObject("mapping") ?: root
                jsonObjectToMap(mapping)
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return when (layer.lowercase()) {
            "bundled" -> bundled
            "runtime" -> runtime
            else -> bundled + runtime
        }
    }

    private fun loadBundledEpgResearch(): Map<String, DaddyliveEpgResearchStore.ResearchMatch> =
        runCatching {
            val text = context.assets.open("daddylive_epg_research.json").bufferedReader().use { it.readText() }
            parseEpgResearchAsset(text)
        }.getOrDefault(emptyMap())

    private fun loadEpgResearchMappings(file: File): Map<String, DaddyliveEpgResearchStore.ResearchMatch> =
        runCatching {
            parseEpgResearchAsset(file.readText())
        }.getOrDefault(emptyMap())

    private fun parseEpgResearchAsset(text: String): Map<String, DaddyliveEpgResearchStore.ResearchMatch> {
        val root = JSONObject(text)
        val mappings = root.optJSONObject("mappings") ?: return emptyMap()
        return buildMap {
            mappings.keys().forEach { channelId ->
                val entry = mappings.optJSONObject(channelId) ?: return@forEach
                val tvgId = entry.optString("tvg_id").trim()
                if (tvgId.isEmpty()) return@forEach
                put(
                    channelId,
                    DaddyliveEpgResearchStore.ResearchMatch(
                        tvgId = tvgId,
                        confidence = entry.optDouble("confidence", 0.0).toFloat(),
                        method = entry.optString("method"),
                        channelName = entry.optString("channel_name").trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    }

    private fun loadBundledEpgIdMap(): Map<String, String> = runCatching {
        val text = context.assets.open("channel_epg_map.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val mapping = root.optJSONObject("mapping") ?: root
        jsonObjectToMap(mapping)
    }.getOrDefault(emptyMap())

    private fun loadBundledJson(assetName: String): Map<String, String> = runCatching {
        val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
        jsonObjectToMap(JSONObject(text))
    }.getOrDefault(emptyMap())

    private fun loadRuntimeJson(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            jsonObjectToMap(JSONObject(file.readText()))
        }.getOrDefault(emptyMap())
    }

    private fun jsonObjectToMap(root: JSONObject): Map<String, String> = buildMap {
        root.keys().forEach { key ->
            val value = root.optString(key).trim()
            if (value.isNotEmpty()) put(key, value)
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val cols = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cols += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        cols += current.toString()
        return cols.map { it.trim('"', ' ') }
    }
}
