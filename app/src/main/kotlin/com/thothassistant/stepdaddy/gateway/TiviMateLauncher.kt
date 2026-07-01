package com.thothassistant.stepdaddy.gateway

import android.content.Context
import com.thothassistant.stepdaddy.gateway.xtream.XtreamCredentials

/** @see TiviMateController */
object TiviMateLauncher {
    fun launch(context: Context): Boolean = TiviMateController.launch(context)

    fun launchForGateway(
        context: Context,
        gatewayBase: String? = null,
        xtreamUsername: String = XtreamCredentials.DEFAULT_USERNAME,
        xtreamPassword: String = XtreamCredentials.DEFAULT_PASSWORD,
    ): Boolean = TiviMateController.launchForGateway(
        context,
        gatewayBase,
        xtreamUsername,
        xtreamPassword,
    )

    fun isInstalled(context: Context): Boolean = TiviMateController.isInstalled(context)

    fun detectInstalledVariant(context: Context): TiviMateVariantProbe =
        TiviMateController.detectInstalledVariant(context)
}
