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
        val customTitle = UpdatePolicy.dialogTitle(manifest)
        val customBody = UpdatePolicy.dialogMessage(manifest)
        val message = buildString {
            if (customBody != null) {
                append(customBody)
            } else {
                append(
                    activity.getString(
                        if (mandatory) {
                            R.string.update_dialog_message_mandatory
                        } else {
                            R.string.update_dialog_message
                        },
                        manifest.versionName,
                        manifest.versionCode,
                    ),
                )
            }
            manifest.releaseNotes?.trim()?.takeIf { it.isNotEmpty() && it != customBody }?.let { notes ->
                append("\n\n")
                append(notes)
            }
        }
        val title = customTitle ?: activity.getString(
            if (mandatory) {
                R.string.update_dialog_title_mandatory
            } else {
                R.string.update_dialog_title_optional
            },
        )
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(!mandatory)
            .setPositiveButton(R.string.update_action_download) { _, _ -> onUpdate() }
        if (!mandatory) {
            builder.setNegativeButton(R.string.update_action_later) { _, _ -> onDismiss?.invoke() }
        }
        val dialog = builder.create()
        if (mandatory) {
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnShowListener {
                dialog.setOnKeyListener { _, keyCode, _ ->
                    keyCode == android.view.KeyEvent.KEYCODE_BACK
                }
            }
        } else {
            dialog.setOnCancelListener { onDismiss?.invoke() }
        }
        return dialog
    }

    fun buildInstallReadyDialog(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        mandatory: Boolean = false,
        onInstall: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (mandatory) {
                    R.string.update_install_ready_title_mandatory
                } else {
                    R.string.update_install_ready_title
                },
            )
            .setMessage(
                activity.getString(
                    if (mandatory) {
                        R.string.update_install_ready_message_mandatory
                    } else {
                        R.string.update_install_ready_message
                    },
                    info.manifest.versionName,
                ),
            )
            .setCancelable(!mandatory)
            .setPositiveButton(R.string.update_action_install) { _, _ -> onInstall() }
        if (!mandatory) {
            builder.setNegativeButton(R.string.update_action_later) { dialog, _ -> dialog.dismiss() }
        }
        val dialog = builder.create()
        if (mandatory) {
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnShowListener {
                dialog.setOnKeyListener { _, keyCode, _ ->
                    keyCode == android.view.KeyEvent.KEYCODE_BACK
                }
            }
        } else {
            dialog.setOnDismissListener { onDismiss?.invoke() }
        }
        return dialog
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
        buildInstallReadyDialog(activity, info, mandatory = false, onInstall = onInstall).show()
    }
}
