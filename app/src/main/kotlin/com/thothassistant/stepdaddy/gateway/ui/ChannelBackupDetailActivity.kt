package com.thothassistant.stepdaddy.gateway.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.thothassistant.stepdaddy.gateway.GatewayApp
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import com.thothassistant.stepdaddy.gateway.model.SupplementFallbackMirror
import com.thothassistant.stepdaddy.gateway.upstream.SupplementFallbackMirrorFactory
import com.thothassistant.stepdaddy.gateway.upstream.SupplementMatchScorer

/** Edit backups attached to one DaddyLive channel. */
class ChannelBackupDetailActivity : AppCompatActivity() {
    private lateinit var app: GatewayApp
    private lateinit var channel: Channel
    private lateinit var attachedLayout: LinearLayout
    private lateinit var suggestedLayout: LinearLayout
    private lateinit var addResultsLayout: LinearLayout
    private lateinit var addSearch: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_backup_detail)
        app = application as GatewayApp

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val found = app.daddyLiveChannels().firstOrNull { it.id == channelId }
        if (found == null) {
            Toast.makeText(this, R.string.channel_backups_channel_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        channel = found

        findViewById<TextView>(R.id.textBackupDetailTitle).text = channel.name
        findViewById<TextView>(R.id.textBackupDetailMeta).text = buildString {
            append("ID ").append(channel.id)
            channel.tvgId?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        attachedLayout = findViewById(R.id.layoutAttachedBackups)
        suggestedLayout = findViewById(R.id.layoutSuggestedBackups)
        addResultsLayout = findViewById(R.id.layoutAddBackupResults)
        addSearch = findViewById(R.id.editAddBackupSearch)

        findViewById<MaterialButton>(R.id.buttonAddBackupSearch).setOnClickListener { searchSupplements() }
        findViewById<MaterialButton>(R.id.buttonBackupDetailBack).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        renderAttached()
        renderSuggestions()
    }

    private fun renderAttached() {
        attachedLayout.removeAllViews()
        val mirrors = app.supplementSource.daddyChannelFallbacks(channel.id)
        if (mirrors.isEmpty()) {
            attachedLayout.addView(muted(getString(R.string.channel_backups_none_attached)))
            return
        }
        mirrors.forEachIndexed { index, mirror ->
            attachedLayout.addView(attachedCard(index + 1, mirror))
        }
    }

    private fun renderSuggestions() {
        suggestedLayout.removeAllViews()
        val denylist = app.supplementSource.consolidationOverrideStore().current().denylist.toSet()
        val suggestions = SupplementFallbackMirrorFactory.suggestMatches(
            daddy = channel,
            supplements = app.supplementSource.channels(),
            alreadyAttached = app.supplementSource.daddyChannelFallbacks(channel.id),
            denylist = denylist,
        )
        if (suggestions.isEmpty()) {
            suggestedLayout.addView(muted(getString(R.string.channel_backups_no_suggestions)))
            return
        }
        for (suggestion in suggestions) {
            suggestedLayout.addView(suggestionCard(suggestion.supplement, suggestion.mirror, suggestion.score))
        }
    }

    private fun searchSupplements() {
        addResultsLayout.removeAllViews()
        val q = addSearch.text?.toString()?.trim().orEmpty()
        if (q.length < 2) {
            Toast.makeText(this, R.string.channel_backups_search_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        val needle = q.lowercase()
        val hits = app.supplementSource.channels()
            .asSequence()
            .filter {
                it.name.lowercase().contains(needle) ||
                    it.id.lowercase().contains(needle) ||
                    it.tvgId.orEmpty().lowercase().contains(needle)
            }
            .take(25)
            .toList()
        if (hits.isEmpty()) {
            addResultsLayout.addView(muted(getString(R.string.channel_backups_no_search_hits)))
            return
        }
        for (hit in hits) {
            addResultsLayout.addView(addCandidateCard(hit))
        }
    }

    private fun attachedCard(ordinal: Int, mirror: SupplementFallbackMirror): LinearLayout {
        val box = sectionBox()
        box.addView(
            TextView(this).apply {
                text = getString(
                    R.string.channel_backups_attached_row,
                    ordinal,
                    mirror.label.ifBlank { "backup" },
                    describeMirror(mirror),
                )
                setTextColor(getColor(R.color.on_background))
                textSize = 14f
            },
        )
        box.addView(
            actionButton(getString(R.string.channel_backups_remove)) {
                confirmRemove(mirror, deny = false)
            },
        )
        box.addView(
            actionButton(getString(R.string.channel_backups_remove_and_block)) {
                confirmRemove(mirror, deny = true)
            },
        )
        return box
    }

    private fun suggestionCard(
        supplement: SupplementChannel,
        mirror: SupplementFallbackMirror,
        score: Int,
    ): LinearLayout {
        val box = sectionBox()
        box.addView(
            TextView(this).apply {
                text = getString(
                    R.string.channel_backups_suggestion_row,
                    score,
                    supplement.name,
                    supplement.providerTag ?: supplement.id.substringBefore(':'),
                    SupplementFallbackMirrorFactory.countryLabel(supplement).ifBlank { "—" },
                )
                setTextColor(getColor(R.color.on_background))
                textSize = 14f
            },
        )
        box.addView(
            actionButton(getString(R.string.channel_backups_accept)) {
                app.supplementSource.attachManualDaddyFallback(
                    daddyChannelId = channel.id,
                    mirror = mirror,
                    supplementName = supplement.name,
                    supplementSource = supplement.providerTag.orEmpty(),
                    country = SupplementFallbackMirrorFactory.countryLabel(supplement),
                )
                invalidatePlaylist()
                Toast.makeText(this, R.string.channel_backups_accepted, Toast.LENGTH_SHORT).show()
                render()
            },
        )
        box.addView(
            actionButton(getString(R.string.channel_backups_reject)) {
                app.supplementSource.consolidationOverrideStore().denyPair(channel.id, mirror)
                Toast.makeText(this, R.string.channel_backups_rejected, Toast.LENGTH_SHORT).show()
                render()
            },
        )
        return box
    }

    private fun addCandidateCard(supplement: SupplementChannel): LinearLayout {
        val mirror = SupplementFallbackMirrorFactory.fromSupplement(supplement)
        val box = sectionBox()
        box.addView(
            TextView(this).apply {
                text = getString(
                    R.string.channel_backups_add_row,
                    supplement.name,
                    supplement.providerTag ?: supplement.id.substringBefore(':'),
                    SupplementFallbackMirrorFactory.countryLabel(supplement).ifBlank { "—" },
                )
                setTextColor(getColor(R.color.on_background))
                textSize = 14f
            },
        )
        box.addView(
            actionButton(getString(R.string.channel_backups_add)) {
                app.supplementSource.attachManualDaddyFallback(
                    daddyChannelId = channel.id,
                    mirror = mirror,
                    supplementName = supplement.name,
                    supplementSource = supplement.providerTag.orEmpty(),
                    country = SupplementFallbackMirrorFactory.countryLabel(supplement),
                )
                invalidatePlaylist()
                Toast.makeText(this, R.string.channel_backups_added, Toast.LENGTH_SHORT).show()
                render()
            },
        )
        return box
    }

    private fun confirmRemove(mirror: SupplementFallbackMirror, deny: Boolean) {
        val message = if (deny) {
            R.string.channel_backups_confirm_remove_block
        } else {
            R.string.channel_backups_confirm_remove
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                app.supplementSource.removeDaddyFallback(channel.id, mirror, denyFutureAutoMatch = deny)
                invalidatePlaylist()
                Toast.makeText(this, R.string.channel_backups_removed, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun invalidatePlaylist() {
        runCatching { app.playlistCache.invalidate() }
    }

    private fun describeMirror(mirror: SupplementFallbackMirror): String = when {
        !mirror.duloChannelId.isNullOrBlank() -> "dulo ${mirror.duloChannelId}"
        !mirror.ntvCdnLiveKey.isNullOrBlank() -> "ntv"
        mirror.streamUrl.isNotBlank() -> mirror.streamUrl.take(48)
        else -> SupplementMatchScorer.mirrorFingerprint(mirror)
    }

    private fun sectionBox(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (10 * resources.displayMetrics.density).toInt() }
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }

    private fun actionButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (6 * resources.displayMetrics.density).toInt() }
            minHeight = (48 * resources.displayMetrics.density).toInt()
            gravity = Gravity.CENTER
            text = label
            setOnClickListener { onClick() }
        }

    private fun muted(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.on_background_muted))
            textSize = 13f
        }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }
}
