package com.thothassistant.stepdaddy.gateway

import android.content.Context

/** @see TiviMateController */
object TiviMateLauncher {
    fun launch(context: Context): Boolean = TiviMateController.launch(context)

    fun launchForGateway(context: Context, gatewayBase: String? = null): Boolean =
        TiviMateController.launchForGateway(context, gatewayBase)

    fun isInstalled(context: Context): Boolean = TiviMateController.isInstalled(context)

    fun detectInstalledVariant(context: Context): TiviMateVariantProbe =
        TiviMateController.detectInstalledVariant(context)
}
