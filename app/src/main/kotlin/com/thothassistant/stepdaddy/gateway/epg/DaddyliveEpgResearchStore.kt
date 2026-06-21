package com.thothassistant.stepdaddy.gateway.epg

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Slowly-built research mappings from [scripts/research-daddylive-tvg-ids.py].
 * Bundled asset is read-only; runtime overlays live under [RUNTIME_RESEARCH_FILE].
 */
class DaddyliveEpgResearchStore private constructor(
    private val bundledLoader: () -> String?,
    private val runtimeFile: File,
    private val quiet: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val byChannelId = mutableMapOf<String, ResearchMatch>()
    private val byNormName = mutableMapOf<String, ResearchMatch>()
    private val bundledChannelIds = mutableSetOf<String>()
    private val runtimeChannelIds = mutableSetOf<String>()

    constructor(context: Context) : this(
        bundledLoader = { loadBundledJson(context.applicationContext) },
        runtimeFile = runtimeResearchFile(context.applicationContext),
    )

    init {
        loadBundled()
        loadRuntime()
        if (!quiet) {
            Log.i(TAG, "Research mappings: ${byChannelId.size} channel ids, ${byNormName.size} names")
        }
    }

    fun lookupByChannelId(channelId: String): ResearchMatch? =
        byChannelId[channelId.trim()]

    fun lookupByName(channelName: String): ResearchMatch? {
        val norm = EpgChannelMapper.normalizeName(channelName)
        if (norm.isEmpty()) return null
        return byNormName[norm]
    }

    fun mappingCount(): Int = byChannelId.size

    fun putRuntimeMapping(
        channelId: String,
        tvgId: String,
        confidence: Float,
        method: String,
        channelName: String? = null,
    ) {
        val id = channelId.trim()
        val tvg = tvgId.trim()
        if (id.isEmpty() || tvg.isEmpty()) return
        val entry = ResearchMatch(
            tvgId = tvg,
            confidence = confidence,
            method = method.trim().ifEmpty { "runtime" },
            channelName = channelName?.trim()?.takeIf { it.isNotEmpty() },
        )
        synchronized(this) {
            byChannelId[id] = entry
            runtimeChannelIds += id
            entry.channelName?.let { name ->
                val norm = EpgChannelMapper.normalizeName(name)
                if (norm.isNotEmpty()) {
                    byNormName[norm] = entry
                }
            }
        }
    }

    fun save() {
        val snapshot = synchronized(this) {
            runtimeChannelIds.associateWith { channelId ->
                requireNotNull(byChannelId[channelId])
            }
        }
        runtimeFile.parentFile?.mkdirs()
        val asset = ResearchAsset(
            version = CURRENT_VERSION,
            mappings = snapshot.mapValues { (_, match) ->
                ResearchMappingEntry(
                    tvg_id = match.tvgId,
                    confidence = match.confidence,
                    method = match.method,
                    channel_name = match.channelName,
                )
            },
        )
        runtimeFile.writeText(json.encodeToString(asset))
        if (!quiet) {
            Log.i(TAG, "Saved ${snapshot.size} runtime research mappings")
        }
    }

    private fun loadBundled() {
        val text = bundledLoader() ?: return
        runCatching {
            ingestAsset(json.decodeFromString<ResearchAsset>(text), bundled = true)
        }.onFailure { exc ->
            if (!quiet) {
                Log.w(TAG, "Bundled research mappings invalid", exc)
            }
        }
    }

    private fun loadRuntime() {
        if (!runtimeFile.isFile) return
        runCatching {
            ingestAsset(json.decodeFromString<ResearchAsset>(runtimeFile.readText()), bundled = false)
        }.onFailure { exc ->
            if (!quiet) {
                Log.w(TAG, "Runtime research mappings load failed", exc)
            }
        }
    }

    private fun ingestAsset(asset: ResearchAsset, bundled: Boolean) {
        asset.mappings.forEach { (channelId, entry) ->
            val id = channelId.trim()
            val tvg = entry.tvg_id.trim()
            if (id.isEmpty() || tvg.isEmpty()) return@forEach
            val match = ResearchMatch(
                tvgId = tvg,
                confidence = entry.confidence,
                method = entry.method,
                channelName = entry.channel_name?.trim()?.takeIf { it.isNotEmpty() },
            )
            synchronized(this) {
                byChannelId[id] = match
                if (bundled) {
                    bundledChannelIds += id
                } else {
                    runtimeChannelIds += id
                }
                match.channelName?.let { name ->
                    val norm = EpgChannelMapper.normalizeName(name)
                    if (norm.isNotEmpty()) {
                        byNormName[norm] = match
                    }
                }
            }
        }
    }

    data class ResearchMatch(
        val tvgId: String,
        val confidence: Float,
        val method: String,
        val channelName: String? = null,
    )

    @Serializable
    private data class ResearchAsset(
        val version: Int = CURRENT_VERSION,
        val mappings: Map<String, ResearchMappingEntry> = emptyMap(),
    )

    @Serializable
    private data class ResearchMappingEntry(
        val tvg_id: String,
        val confidence: Float = 0.0f,
        val method: String = "",
        val channel_name: String? = null,
    )

    companion object {
        private const val TAG = "DaddyliveEpgResearch"
        private const val CURRENT_VERSION = 1
        const val RUNTIME_RESEARCH_FILE = "epg/daddylive_epg_research.json"

        fun runtimeResearchFile(context: Context): File =
            File(context.filesDir, RUNTIME_RESEARCH_FILE)

        private fun loadBundledJson(context: Context): String? =
            runCatching {
                context.assets.open(EpgConfig.RESEARCH_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()

        internal fun forTest(
            bundledJson: String?,
            runtimeFile: File,
        ): DaddyliveEpgResearchStore = DaddyliveEpgResearchStore(
            bundledLoader = { bundledJson },
            runtimeFile = runtimeFile,
            quiet = true,
        )
    }
}
