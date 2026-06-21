package com.thothassistant.stepdaddy.gateway.update

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.thothassistant.stepdaddy.gateway.R

object AppUpdateDialogHelper {
    fun buildUpdateDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        mandatory: Boolean,
        onUpdate: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
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
            builder.setNegativeButton(R.string.update_action_later) { _, _ -> onDismiss?.invoke() }
        }
        val dialog = builder.create()
        if (mandatory) {
            dialog.setCanceledOnTouchOutside(false)
        } else {
            dialog.setOnCancelListener { onDismiss?.invoke() }
        }
        return dialog
    }

    fun buildInstallReadyDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        onInstall: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        return AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_ready_title)
            .setMessage(
                activity.getString(
                    R.string.update_install_ready_message,
                    info.manifest.versionName,
                ),
            )
            .setPositiveButton(R.string.update_action_install) { _, _ -> onInstall() }
            .setNegativeButton(R.string.update_action_later) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { onDismiss?.invoke() }
            .create()
    }

    /** @deprecated use [buildUpdateDialog] via [AppUpdateCoordinator] */
    fun showUpdateDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        mandatory: Boolean,
        onUpdate: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing) return
        buildUpdateDialog(activity, info, mandatory, onUpdate, onDismiss).show()
    }

    /** @deprecated use [buildInstallReadyDialog] via [AppUpdateCoordinator] */
    fun showInstallReadyDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        onInstall: () -> Unit,
    ) {
        if (activity.isFinishing) return
        buildInstallReadyDialog(activity, info, onInstall).show()
    }
}
