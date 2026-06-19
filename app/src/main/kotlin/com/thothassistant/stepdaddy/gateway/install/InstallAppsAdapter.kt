package com.thothassistant.stepdaddy.gateway.install

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.thothassistant.stepdaddy.gateway.R
import java.util.Locale

class InstallAppsAdapter(
    private val iconLoader: InstallAppIconLoader,
    private val onInstallClick: (InstallAppUiItem) -> Unit,
    private val onSelectionChanged: (InstallAppUiItem, Boolean) -> Unit,
    private val onRowFocus: (Int) -> Unit = {},
    private val onFocusRowRequest: (Int) -> Unit = {},
    private val onFocusToolbarRequest: () -> Unit = {},
) : ListAdapter<InstallAppUiItem, InstallAppsAdapter.ViewHolder>(DiffCallback) {

    var toolbarDownTargetId: Int = View.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_install_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.cardRoot)
        private val checkSelect: CheckBox = itemView.findViewById(R.id.checkSelect)
        private val imageIcon: ImageView = itemView.findViewById(R.id.imageIcon)
        private val textName: TextView = itemView.findViewById(R.id.textName)
        private val textSubtitle: TextView = itemView.findViewById(R.id.textSubtitle)
        private val textDescription: TextView = itemView.findViewById(R.id.textDescription)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val buttonInstall: MaterialButton = itemView.findViewById(R.id.buttonInstall)

        fun bind(item: InstallAppUiItem, position: Int) {
            val entry = item.entry
            val context = itemView.context

            textName.text = entry.name
            textSubtitle.text = buildSubtitle(context, item)
            textDescription.text = entry.description.ifBlank {
                context.getString(R.string.install_apps_no_description)
            }

            val statusParts = buildList {
                item.installedVersion?.let { add(context.getString(R.string.install_apps_installed_version, it)) }
                if (item.statusText.isNotBlank()) add(item.statusText)
            }
            textStatus.text = statusParts.joinToString(" · ")
            textStatus.visibility = if (statusParts.isEmpty()) View.GONE else View.VISIBLE

            progressBar.visibility =
                if (item.state == InstallAppState.DOWNLOADING) View.VISIBLE else View.GONE
            progressBar.progress = item.progressPercent

            checkSelect.isChecked = item.selected
            checkSelect.setOnCheckedChangeListener(null)
            checkSelect.setOnCheckedChangeListener { _, checked ->
                onSelectionChanged(item, checked)
            }

            buttonInstall.text = when {
                item.installedVersion != null && item.state == InstallAppState.IDLE ->
                    context.getString(R.string.install_apps_action_reinstall)
                item.state == InstallAppState.DOWNLOADING ->
                    context.getString(R.string.install_apps_action_downloading)
                item.state == InstallAppState.INSTALLING ->
                    context.getString(R.string.install_apps_action_installing)
                item.state == InstallAppState.DONE ->
                    context.getString(R.string.install_apps_action_done)
                item.state == InstallAppState.FAILED ->
                    context.getString(R.string.install_apps_action_retry)
                else -> context.getString(R.string.install_apps_action_install)
            }
            buttonInstall.isEnabled =
                item.state != InstallAppState.DOWNLOADING && item.state != InstallAppState.INSTALLING
            buttonInstall.setOnClickListener { onInstallClick(item) }

            iconLoader.loadInto(imageIcon, item)
            wireFocusChain(position)
            val verticalKeyListener = verticalNavigationListener(position)
            cardRoot.setOnKeyListener(verticalKeyListener)
            checkSelect.setOnKeyListener(verticalKeyListener)
            buttonInstall.setOnKeyListener(verticalKeyListener)
        }

        private fun verticalNavigationListener(position: Int): View.OnKeyListener =
            View.OnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (position < itemCount - 1) {
                            onFocusRowRequest(position + 1)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position > 0) {
                            onFocusRowRequest(position - 1)
                            true
                        } else {
                            onFocusToolbarRequest()
                            true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (view.id == R.id.cardRoot) {
                            checkSelect.isChecked = !checkSelect.isChecked
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }

        fun recycle() {
            iconLoader.clear(imageIcon)
        }

        private fun wireFocusChain(position: Int) {
            val isFirst = position == 0

            if (isFirst && toolbarDownTargetId != View.NO_ID) {
                checkSelect.nextFocusUpId = toolbarDownTargetId
                cardRoot.nextFocusUpId = toolbarDownTargetId
                buttonInstall.nextFocusUpId = toolbarDownTargetId
            } else {
                checkSelect.nextFocusUpId = View.NO_ID
                cardRoot.nextFocusUpId = View.NO_ID
                buttonInstall.nextFocusUpId = View.NO_ID
            }

            cardRoot.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onRowFocus(position)
            }

            cardRoot.setOnClickListener {
                checkSelect.isChecked = !checkSelect.isChecked
            }
        }

        private fun buildSubtitle(context: android.content.Context, item: InstallAppUiItem): String {
            val entry = item.entry
            val sourceLabel = when (entry.source) {
                InstallAppsCatalogRepository.SOURCE_TV2024 ->
                    context.getString(R.string.install_apps_source_tv2024)
                InstallAppsCatalogRepository.SOURCE_DOCSQUIFFY ->
                    context.getString(R.string.install_apps_source_docsquiffy)
                else -> entry.source
            }
            val parts = mutableListOf<String>()
            parts += sourceLabel
            entry.version?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
            entry.fileSizeBytes?.takeIf { it > 0 }?.let { parts += formatFileSize(it) }
            return parts.joinToString(" · ")
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824L ->
                    String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576L ->
                    String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
                bytes >= 1024L ->
                    String.format(Locale.US, "%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<InstallAppUiItem>() {
        override fun areItemsTheSame(oldItem: InstallAppUiItem, newItem: InstallAppUiItem): Boolean =
            oldItem.entry.id == newItem.entry.id

        override fun areContentsTheSame(oldItem: InstallAppUiItem, newItem: InstallAppUiItem): Boolean =
            oldItem == newItem
    }
}
