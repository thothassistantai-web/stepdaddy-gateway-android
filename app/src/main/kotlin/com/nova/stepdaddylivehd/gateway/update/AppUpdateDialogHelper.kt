package com.nova.stepdaddylivehd.gateway.update

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nova.stepdaddylivehd.gateway.R

object AppUpdateDialogHelper {
    fun showUpdateDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        mandatory: Boolean,
        onUpdate: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing) return
        val manifest = info.manifest
        val message = buildString {
            append(
                activity.getString(
                    R.string.update_dialog_message,
                    manifest.versionName,
                    manifest.versionCode,
                ),
            )
            manifest.releaseNotes?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                append("\n\n")
                append(notes)
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (mandatory) {
                    R.string.update_dialog_title_mandatory
                } else {
                    R.string.update_dialog_title_optional
                },
            )
            .setMessage(message)
            .setCancelable(!mandatory)
            .setPositiveButton(R.string.update_action_download) { _, _ -> onUpdate() }
        if (!mandatory) {
            builder.setNegativeButton(R.string.update_action_later) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
        }
        val dialog = builder.create()
        if (mandatory) {
            dialog.setCanceledOnTouchOutside(false)
        }
        dialog.show()
    }

    fun showInstallReadyDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        onInstall: () -> Unit,
    ) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_ready_title)
            .setMessage(
                activity.getString(
                    R.string.update_install_ready_message,
                    info.manifest.versionName,
                ),
            )
            .setPositiveButton(R.string.update_action_install) { _, _ -> onInstall() }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }
}
