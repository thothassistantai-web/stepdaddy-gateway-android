package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Adult Swim 24/7 marathon streams (Turner CDN HLS).
 * @see <a href="https://www.adultswim.com/videos/streams">adultswim.com/videos/streams</a>
 */
object AdultSwimStreamsConfig {
    /** Published under [GroupTitleResolver.ENTERTAINMENT] with 24/7 Adult Swim titles. */
    const val GROUP_TITLE = GroupTitleResolver.ENTERTAINMENT

    const val REFERER = "https://www.adultswim.com/"

    const val ORIGIN = "https://www.adultswim.com"

    const val PROVIDER_TAG = "Adult Swim"

    const val CDN_BASE = "https://adultswim-vodlive.cdn.turner.com/live"

    const val PLAYER_NAME = "top-2.18.1"

    const val PROBE_TIMEOUT_MS = 25_000L

    /** Cap total probe wait when a previous adultswim: cache exists. */
    const val PROBE_BUDGET_MS = 45_000L

    const val MAX_CONCURRENT_PROBES = 2

    data class MarathonStream(
        val slug: String,
        val name: String,
        val tvgId: String?,
        val logo: String?,
    )

    /** Known marathon slugs; only rows that pass HLS probe at sync are published. */
    val CATALOG: List<MarathonStream> = listOf(
        MarathonStream("rick-and-morty", "Rick and Morty", "AdultSwimRickandMorty.us", "https://i.imgur.com/uPV5CT1.png"),
        MarathonStream("robot-chicken", "Robot Chicken", "AdultSwimRobotChicken.us", "https://i.imgur.com/E6EJ14j.png"),
        MarathonStream("metalocalypse", "Metalocalypse", "AdultSwimMetalocalypse.us", "https://i.imgur.com/CaKq6Mt.png"),
        MarathonStream("aqua-teen", "Aqua Teen Hunger Force", "AdultSwimAquaTeenHungerForce.us", "https://i.imgur.com/cvnniFH.png"),
        MarathonStream("samurai-jack", "Samurai Jack", "AdultSwimSamuraiJack.us", "https://i.imgur.com/UOZ4VTH.png"),
        MarathonStream("off-the-air", "Off the Air", "AdultSwimOffTheAir.us", "https://i.imgur.com/X2qhBpO.png"),
        MarathonStream("channel-5", "Channel 5", "AdultSwimChannel5.us", "https://i.imgur.com/G9TyeCN.png"),
        MarathonStream("black-jesus", "Black Jesus", "AdultSwimBlackJesus.us", "https://i.imgur.com/QWzEK8i.png"),
        MarathonStream("DREAM-CORP-LLC", "Dream Corp LLC", "AdultSwimDreamCorpLLC.us", "https://i.imgur.com/TSuWOBP.png"),
        MarathonStream("infomercials", "Infomercials", "AdultSwimInfomercials.us", "https://i.imgur.com/gu8luP0.png"),
        MarathonStream("lsotl", "Last Stream on the Left", "AdultSwimLastStreamOnTheLeft.us", "https://i.imgur.com/bnZCZD2.png"),
        MarathonStream("primal", "Primal", "AdultSwimPrimal.us", "https://i.imgur.com/fRysIrL.png"),
        MarathonStream("eric-andre", "The Eric Andre Show", "AdultSwimTheEricAndreShow.us", "https://i.imgur.com/47za7Yq.png"),
        MarathonStream("venture-bros", "The Venture Bros", "AdultSwimTheVentureBros.us", "https://i.imgur.com/ZwNmt8Y.png"),
        MarathonStream("ypf", "Your Pretty Face Is Going to Hell", "AdultSwimYourPrettyFaceIsGoingToHell.us", "https://i.imgur.com/uLoRE4F.png"),
        MarathonStream("toonami", "Toonami", "Toonami.fr", "https://i.imgur.com/U7qh4yF.png"),
        MarathonStream("williams-stream", "Williams Stream", null, null),
    )

    fun masterPlaylistUrl(slug: String): String =
        "$CDN_BASE/${slug.trim()}/stream_de.m3u8?playername=$PLAYER_NAME"
}
