package com.thothassistant.stepdaddy.gateway.install

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.R

class InstallAppsAdapter(
    private val onInstallClick: (InstallAppUiItem) -> Unit,
    private val onSelectionChanged: (InstallAppUiItem, Boolean) -> Unit,
) : ListAdapter<InstallAppUiItem, InstallAppsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_install_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkSelect: CheckBox = itemView.findViewById(R.id.checkSelect)
        private val textName: TextView = itemView.findViewById(R.id.textName)
        private val textDescription: TextView = itemView.findViewById(R.id.textDescription)
        private val textSource: TextView = itemView.findViewById(R.id.textSource)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val buttonInstall: MaterialButton = itemView.findViewById(R.id.buttonInstall)

        fun bind(item: InstallAppUiItem) {
            val entry = item.entry
            textName.text = entry.name
            textDescription.text = entry.description.ifBlank {
                itemView.context.getString(R.string.install_apps_no_description)
            }
            textSource.text = when (entry.source) {
                InstallAppsCatalogRepository.SOURCE_TV2024 ->
                    itemView.context.getString(R.string.install_apps_source_tv2024)
                InstallAppsCatalogRepository.SOURCE_DOCSQUIFFY ->
                    itemView.context.getString(R.string.install_apps_source_docsquiffy)
                else -> entry.source
            }

            val statusParts = buildList {
                item.installedVersion?.let { add("Installed: v$it") }
                entry.version?.let { add("Catalog: v$it") }
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
                    itemView.context.getString(R.string.install_apps_action_reinstall)
                item.state == InstallAppState.DOWNLOADING ->
                    itemView.context.getString(R.string.install_apps_action_downloading)
                item.state == InstallAppState.INSTALLING ->
                    itemView.context.getString(R.string.install_apps_action_installing)
                item.state == InstallAppState.DONE ->
                    itemView.context.getString(R.string.install_apps_action_done)
                item.state == InstallAppState.FAILED ->
                    itemView.context.getString(R.string.install_apps_action_retry)
                else -> itemView.context.getString(R.string.install_apps_action_install)
            }
            buttonInstall.isEnabled =
                item.state != InstallAppState.DOWNLOADING && item.state != InstallAppState.INSTALLING
            buttonInstall.setOnClickListener { onInstallClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<InstallAppUiItem>() {
        override fun areItemsTheSame(oldItem: InstallAppUiItem, newItem: InstallAppUiItem): Boolean =
            oldItem.entry.id == newItem.entry.id

        override fun areContentsTheSame(oldItem: InstallAppUiItem, newItem: InstallAppUiItem): Boolean =
            oldItem == newItem
    }
}
