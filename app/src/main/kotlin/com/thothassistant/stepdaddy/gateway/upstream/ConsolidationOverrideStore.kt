package com.thothassistant.stepdaddy.gateway.upstream

import android.content.Context
import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User corrections for DaddyLive supplement backups: manual attachments and
 * auto-match denylist pairs. Survives supplement refresh.
 */
class ConsolidationOverrideStore(context: Context) {
    private val file = File(context.filesDir, "supplement/consolidation_overrides.json").also {
        it.parentFile?.mkdirs()
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshot = AtomicReference(load())

    fun current(): ConsolidationOverrides = snapshot.get()

    fun isDenied(daddyChannelId: String, mirror: SupplementFallbackMirror): Boolean {
        val key = SupplementMatchScorer.pairKey(
            daddyChannelId,
            SupplementMatchScorer.mirrorFingerprint(mirror),
        )
        return key in current().denylist
    }

    fun denyPair(daddyChannelId: String, mirror: SupplementFallbackMirror) {
        val key = SupplementMatchScorer.pairKey(
            daddyChannelId,
            SupplementMatchScorer.mirrorFingerprint(mirror),
        )
        update { cur ->
            cur.copy(denylist = (cur.denylist + key).distinct().sorted())
        }
    }

    fun allowPair(daddyChannelId: String, mirror: SupplementFallbackMirror) {
        val key = SupplementMatchScorer.pairKey(
            daddyChannelId,
            SupplementMatchScorer.mirrorFingerprint(mirror),
        )
        update { cur -> cur.copy(denylist = cur.denylist.filterNot { it == key }) }
    }

    fun addManualAttachment(
        daddyChannelId: String,
        mirror: SupplementFallbackMirror,
        supplementName: String = "",
        supplementSource: String = "",
        country: String = "",
    ) {
        val fingerprint = SupplementMatchScorer.mirrorFingerprint(mirror)
        update { cur ->
            val without = cur.manualAttachments.filterNot {
                it.daddyChannelId == daddyChannelId &&
                    SupplementMatchScorer.mirrorFingerprint(it.mirror) == fingerprint
            }
            val deniedKey = SupplementMatchScorer.pairKey(daddyChannelId, fingerprint)
            cur.copy(
                denylist = cur.denylist.filterNot { it == deniedKey },
                manualAttachments = without + ManualFallbackAttachment(
                    daddyChannelId = daddyChannelId,
                    mirror = mirror,
                    supplementName = supplementName,
                    supplementSource = supplementSource,
                    country = country,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun removeManualAttachment(daddyChannelId: String, mirror: SupplementFallbackMirror) {
        val fingerprint = SupplementMatchScorer.mirrorFingerprint(mirror)
        update { cur ->
            cur.copy(
                manualAttachments = cur.manualAttachments.filterNot {
                    it.daddyChannelId == daddyChannelId &&
                        SupplementMatchScorer.mirrorFingerprint(it.mirror) == fingerprint
                },
            )
        }
    }

    fun clearAll() {
        update { ConsolidationOverrides() }
    }

    private fun update(block: (ConsolidationOverrides) -> ConsolidationOverrides) {
        while (true) {
            val cur = snapshot.get()
            val next = block(cur)
            if (snapshot.compareAndSet(cur, next)) {
                persist(next)
                return
            }
        }
    }

    private fun load(): ConsolidationOverrides {
        if (!file.exists()) return ConsolidationOverrides()
        return runCatching {
            json.decodeFromString<ConsolidationOverrides>(file.readText())
        }.getOrElse { exc ->
            Log.w(TAG, "Failed to read consolidation overrides", exc)
            ConsolidationOverrides()
        }
    }

    private fun persist(value: ConsolidationOverrides) {
        runCatching {
            file.writeText(json.encodeToString(value))
        }.onFailure { exc ->
            Log.w(TAG, "Failed to write consolidation overrides", exc)
        }
    }

    companion object {
        private const val TAG = "ConsolOverrides"
    }
}

@Serializable
data class ConsolidationOverrides(
    val denylist: List<String> = emptyList(),
    val manualAttachments: List<ManualFallbackAttachment> = emptyList(),
)

@Serializable
data class ManualFallbackAttachment(
    val daddyChannelId: String,
    val mirror: SupplementFallbackMirror,
    val supplementName: String = "",
    val supplementSource: String = "",
    val country: String = "",
    val createdAtMs: Long = 0L,
)

object SupplementFallbackOverridesApplier {
    fun apply(
        autoFallbacks: Map<String, List<SupplementFallbackMirror>>,
        overrides: ConsolidationOverrides,
    ): Map<String, List<SupplementFallbackMirror>> {
        val denylist = overrides.denylist.toSet()
        val merged = mutableMapOf<String, MutableList<SupplementFallbackMirror>>()

        for ((channelId, mirrors) in autoFallbacks) {
            for (mirror in mirrors) {
                val key = SupplementMatchScorer.pairKey(
                    channelId,
                    SupplementMatchScorer.mirrorFingerprint(mirror),
                )
                if (key in denylist) continue
                merged.getOrPut(channelId) { mutableListOf() } += mirror
            }
        }

        for (manual in overrides.manualAttachments) {
            val channelId = manual.daddyChannelId.trim()
            if (channelId.isEmpty()) continue
            val fingerprint = SupplementMatchScorer.mirrorFingerprint(manual.mirror)
            val key = SupplementMatchScorer.pairKey(channelId, fingerprint)
            if (key in denylist) continue
            val list = merged.getOrPut(channelId) { mutableListOf() }
            if (list.any { SupplementMatchScorer.mirrorFingerprint(it) == fingerprint }) continue
            list += manual.mirror
        }

        return merged.mapValues { (_, mirrors) -> mirrors.toList() }
    }
}
