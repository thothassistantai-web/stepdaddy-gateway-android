package com.thothassistant.stepdaddy.gateway.ui

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.thothassistant.stepdaddy.gateway.R

class MainDashboardViews(root: View) {
    val textTitle: TextView = root.findViewById(R.id.textTitle)
    val textVersion: TextView = root.findViewById(R.id.textVersion)
    val textClock: TextView = root.findViewById(R.id.textClock)
    val buttonHeaderSettings: ImageButton = root.findViewById(R.id.buttonHeaderSettings)
    val buttonHeaderUpdate: ImageButton = root.findViewById(R.id.buttonHeaderUpdate)

    val buttonToggleServer: MaterialButton = root.findViewById(R.id.buttonToggleServer)
    val buttonRestart: MaterialButton = root.findViewById(R.id.buttonRestart)
    val textStatus: TextView = root.findViewById(R.id.textStatus)
    val textPort: TextView = root.findViewById(R.id.textPort)
    val textNetworkMode: TextView = root.findViewById(R.id.textNetworkMode)
    val textPeerBanner: TextView = root.findViewById(R.id.textPeerBanner)

    val buttonSettings: MaterialButton = root.findViewById(R.id.buttonSettings)
    val buttonInstallApps: MaterialButton = root.findViewById(R.id.buttonInstallApps)
    val buttonAbout: MaterialButton = root.findViewById(R.id.buttonAbout)

    val switchAutoStart: SwitchCompat = root.findViewById(R.id.switchAutoStart)
    val switchLaunchTivimate: SwitchCompat = root.findViewById(R.id.switchLaunchTivimate)
    val switchBoot: SwitchCompat = root.findViewById(R.id.switchBoot)
    val switchTivimateWatch: SwitchCompat = root.findViewById(R.id.switchTivimateWatch)

    val textPlaylistUrl: TextView = root.findViewById(R.id.textPlaylistUrl)
    val textTiviMatePlaylistState: TextView = root.findViewById(R.id.textTiviMatePlaylistState)
    val buttonCopyPlaylist: MaterialButton = root.findViewById(R.id.buttonCopyPlaylist)
    val buttonOpenPlaylist: MaterialButton = root.findViewById(R.id.buttonOpenPlaylist)
    val buttonQrPlaylist: MaterialButton = root.findViewById(R.id.buttonQrPlaylist)
    val buttonLaunchTivimate: MaterialButton = root.findViewById(R.id.buttonLaunchTivimate)
    val buttonInstallTivimate: MaterialButton = root.findViewById(R.id.buttonInstallTivimate)

    val textEpgUrl: TextView = root.findViewById(R.id.textEpgUrl)
    val textEpgStatus: TextView = root.findViewById(R.id.textEpgStatus)
    val buttonCopyEpg: MaterialButton = root.findViewById(R.id.buttonCopyEpg)
    val buttonOpenEpg: MaterialButton = root.findViewById(R.id.buttonOpenEpg)

    val imageHealthBadge: ImageView = root.findViewById(R.id.imageHealthBadge)
    val textHealthStatus: TextView = root.findViewById(R.id.textHealthStatus)
    val textHealthSubtitle: TextView = root.findViewById(R.id.textHealthSubtitle)
    val layoutSpecialEventsHealth: View = root.findViewById(R.id.layoutSpecialEventsHealth)
    val textSpecialEventsStatus: TextView = root.findViewById(R.id.textSpecialEventsStatus)
    val textSpecialEventsCounts: TextView = root.findViewById(R.id.textSpecialEventsCounts)
    val textSpecialEventsLastScrape: TextView = root.findViewById(R.id.textSpecialEventsLastScrape)
    val textActivity: TextView = root.findViewById(R.id.textActivity)
    val textErrors: TextView = root.findViewById(R.id.textErrors)
    val containerProviderBars: LinearLayout = root.findViewById(R.id.containerProviderBars)
    val textProvidersTotal: TextView = root.findViewById(R.id.textProvidersTotal)
    val containerCategoryBars: LinearLayout = root.findViewById(R.id.containerCategoryBars)

    val viewFooterStatusDot: View = root.findViewById(R.id.viewFooterStatusDot)
    val textFooterStatus: TextView = root.findViewById(R.id.textFooterStatus)
    val textFooterUptime: TextView = root.findViewById(R.id.textFooterUptime)
    val textFooterClients: TextView = root.findViewById(R.id.textFooterClients)
    val textFooterMemory: TextView = root.findViewById(R.id.textFooterMemory)
    val textFooterUpdate: TextView = root.findViewById(R.id.textFooterUpdate)
    val buttonFooterScrollTop: ImageButton = root.findViewById(R.id.buttonFooterScrollTop)
    val scrollDashboard: ScrollView = root.findViewById(R.id.scrollDashboard)
    val textGatewayHud: TextView = root.findViewById(R.id.textGatewayHud)
}
