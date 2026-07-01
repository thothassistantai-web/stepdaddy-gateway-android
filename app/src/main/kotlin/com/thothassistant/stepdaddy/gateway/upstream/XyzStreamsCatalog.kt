package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Expanded xyzstreams channel catalog — verified against 247v2 + TV Guide EPG keys.
 */
object XyzStreamsCatalog {
    val KnownEpgStreamIds: Map<String, String> = mapOf(
        "CNNHD" to "cnn",
        "CNN" to "cnn",
        "HLNHD" to "hln",
        "CNBCHD" to "cnbc",
        "CNBC" to "cnbc",
        "FNCHD" to "foxnews",
        "NEWSMAXHD" to "newsmax",
        "MSNBCHD" to "msnbc",
        "BBCAMHD" to "bbcamerica",
        "BBC" to "bbcamerica",
        "MLBHD" to "mlbnetwork",
        "NBAHD" to "nbatv",
        "NHLTVHD" to "nhlnetwork",
        "GOLFHD" to "golfchannel",
        "TENNISHD" to "tennischannel",
        "ACCNHD" to "accnetwork",
        "SECNETHD" to "secnetwork",
        "BEINXTRA" to "beinsports",
        "HGTVHD" to "hgtv",
        "HGTV" to "hgtv",
        "FOODHD" to "foodnetwork",
        "FOOD" to "foodnetwork",
        "TDC-HD" to "discovery",
        "DSC" to "discovery",
        "ANIMALHD" to "discovery",
        "HIST" to "history",
        "COMEDYHD" to "comedycentral",
        "MTVHD" to "mtv",
        "MTV" to "mtv",
        "VH1HD" to "vh1",
        "VH1" to "vh1",
        "BETHD" to "bet",
        "BET" to "bet",
        "ETV-HD" to "e",
        "E!" to "e",
        "FXHD" to "fx",
        "FX" to "fx",
        "FXXHD" to "fxx",
        "FXX" to "fxx",
        "SYFYHD" to "syfy",
        "SyFyHD" to "syfy",
        "HALLMARKHD" to "hallmarkchannel",
        "LIFEHD" to "lifetime",
        "LIFE" to "lifetime",
        "IONHD" to "ion",
        "ION" to "ion",
        "DISNEYJRHD" to "disneyjr",
        "NICKJRHD" to "nickjr",
        "HBOHD" to "hbomax",
        "HBO" to "hbomax",
        "MAXHD" to "hbomax",
        "MAX" to "hbomax",
        "STARZHD" to "starz",
        "CINEMAXHD" to "cinemax",
        "5STARMAXHD" to "cinemax",
        "MGM+" to "mgm",
        "MGM+HIT" to "mgm",
        "MGM+MAR" to "mgm",
        "TRUTVHD" to "trutv",
        "TVLANDHD" to "tvland",
        "TVLAND" to "tvland",
        "NGEOWILDHD" to "natgeowild",
        "FOXWTHR" to "foxweather",
        "TELEMUNDHD" to "tele",
        "TELMUN" to "tele",
        "UNIMAS" to "universo",
        "UNI" to "universo",
        "TOONHD" to "cartoonnetworkadultswim",
        "CARTOON NETWORK" to "cartoonnetworkadultswim",
    )

    /** Extra slug aliases for discovery probes. */
    val StreamIdAliases: Map<String, List<String>> = mapOf(
        "PARAMOUNT" to listOf("paramountnetwork"),
        "PAR" to listOf("paramountnetwork"),
        "SHOWTIME" to listOf("showtime"),
        "SHO" to listOf("showtime"),
        "CSPANHD" to listOf("cspan"),
        "CSPAN2HD" to listOf("cspan2"),
        "WPIX-DT" to listOf("cw"),
        "CW" to listOf("cw"),
    )

    val CATALOG: List<XyzStreamsConfig.ChannelRow> = buildList {
        addAll(coreUsChannels())
        addAll(expandedVerifiedChannels())
    }

    fun displayNameForEpgKey(epgKey: String): String =
        DisplayNames[epgKey.uppercase()] ?: epgKey
            .replace("-DT", "")
            .replace(Regex("HD$"), "")
            .replace('_', ' ')
            .trim()
            .ifEmpty { epgKey }

    fun groupTitleForEpgKey(epgKey: String): String {
        val upper = epgKey.uppercase()
        return when {
            upper.contains("ESPN") || upper.contains("NFL") || upper.contains("MLB") ||
                upper.contains("NBA") || upper.contains("NHL") || upper.contains("GOLF") ||
                upper.contains("TENNIS") || upper.contains("SPORT") || upper.contains("FS") ||
                upper.contains("ACCN") || upper.contains("SEC") || upper.contains("BEIN") ||
                upper.contains("BTN") -> GroupTitleResolver.SPORTS
            upper.contains("NEWS") || upper.contains("CNN") || upper.contains("CNBC") ||
                upper.contains("MSNBC") || upper.contains("FNC") || upper.contains("BBC") ||
                upper.contains("CSPAN") -> GroupTitleResolver.NEWS
            upper.contains("DISNEY") || upper.contains("NICK") || upper.contains("TOON") ||
                upper.contains("CARTOON") || upper.contains("PBSKIDS") -> GroupTitleResolver.KIDS
            upper.contains("HALL") || upper.contains("HBO") || upper.contains("MAX") ||
                upper.contains("STARZ") || upper.contains("CINE") || upper.contains("MGM") ||
                upper.contains("SHOW") -> GroupTitleResolver.MOVIES
            upper.contains("HIST") || upper.contains("DISCOVERY") || upper.contains("ANIMAL") ||
                upper.contains("NAT") || upper.contains("TRAVEL") || upper.contains("ID") ||
                upper.contains("INV") -> GroupTitleResolver.DOCUMENTARY
            upper.contains("TELE") || upper.contains("UNI") || upper.contains("GAL") ||
                upper.contains("ESPA") -> GroupTitleResolver.EN_ESPANOL
            upper.contains("WCBS") || upper.contains("WABC") || upper.contains("WNYW") ||
                upper.contains("WNBC") || upper.contains("WPIX") || upper.contains("CW") ||
                upper.contains("PBS") -> GroupTitleResolver.LOCAL_CHANNELS
            else -> GroupTitleResolver.ENTERTAINMENT
        }
    }

    fun tvgIdFor(displayName: String, streamId: String): String {
        TvgIds[streamId.lowercase()]?.let { return it }
        val slug = displayName.replace(Regex("[^A-Za-z0-9]+"), "")
        return if (slug.isEmpty()) "${streamId.uppercase()}.us" else "$slug.us"
    }

    private val TvgIds = mapOf(
        "cnn" to "CNN.us",
        "hln" to "HLN.us",
        "cnbc" to "CNBC.us",
        "foxnews" to "FoxNews.us",
        "newsmax" to "Newsmax.us",
        "bbcamerica" to "BBCAmerica.us",
        "mlbnetwork" to "MLBNetwork.us",
        "nbatv" to "NBATV.us",
        "nhlnetwork" to "NHLNetwork.us",
        "golfchannel" to "GolfChannel.us",
        "tennischannel" to "TennisChannel.us",
        "accnetwork" to "ACCNetwork.us",
        "secnetwork" to "SECNetwork.us",
        "beinsports" to "beINSports.us",
        "hgtv" to "HGTV.us",
        "foodnetwork" to "FoodNetwork.us",
        "discovery" to "DiscoveryChannel.us",
        "history" to "History.us",
        "comedycentral" to "ComedyCentral.us",
        "fx" to "FX.us",
        "fxx" to "FXX.us",
        "syfy" to "Syfy.us",
        "hallmarkchannel" to "HallmarkChannel.us",
        "lifetime" to "Lifetime.us",
        "ion" to "ION.us",
        "disneyjr" to "DisneyJunior.us",
        "nickjr" to "NickJr.us",
        "hbomax" to "HBOMax.us",
        "starz" to "Starz.us",
        "cinemax" to "Cinemax.us",
        "mgm" to "MGMPlus.us",
        "trutv" to "TruTV.us",
        "tvland" to "TVLand.us",
        "natgeowild" to "NatGeoWild.us",
        "foxweather" to "FoxWeather.us",
        "tele" to "Telemundo.us",
        "universo" to "Universo.us",
        "mtv" to "MTV.us",
        "vh1" to "VH1.us",
        "bet" to "BET.us",
        "e" to "EEntertainment.us",
        "paramountnetwork" to "ParamountNetwork.us",
    )

    private val DisplayNames = mapOf(
        "CNNHD" to "CNN",
        "HLNHD" to "HLN",
        "CNBCHD" to "CNBC",
        "FNCHD" to "Fox News",
        "NEWSMAXHD" to "Newsmax",
        "BBCAMHD" to "BBC America",
        "MLBHD" to "MLB Network",
        "NBAHD" to "NBA TV",
        "NHLTVHD" to "NHL Network",
        "GOLFHD" to "Golf Channel",
        "TENNISHD" to "Tennis Channel",
        "ACCNHD" to "ACC Network",
        "SECNETHD" to "SEC Network",
        "HGTVHD" to "HGTV",
        "FOODHD" to "Food Network",
        "TDC-HD" to "Discovery Channel",
        "ANIMALHD" to "Animal Planet",
        "HIST" to "History",
        "COMEDYHD" to "Comedy Central",
        "HALLMARKHD" to "Hallmark Channel",
        "LIFEHD" to "Lifetime",
        "IONHD" to "ION",
        "DISNEYJRHD" to "Disney Junior",
        "NICKJRHD" to "Nick Jr.",
        "STARZHD" to "Starz",
        "NGEOWILDHD" to "Nat Geo Wild",
        "FOXWTHR" to "Fox Weather",
        "TELEMUNDHD" to "Telemundo",
        "TRUTVHD" to "TruTV",
        "TVLANDHD" to "TV Land",
        "SyFyHD" to "Syfy",
        "ETV-HD" to "E!",
    )

    private fun sling(
        streamId: String,
        displayName: String,
        tvgId: String,
        epgKeys: Set<String>,
        groupTitle: String,
        logo: String? = null,
        upstreamKind: XyzStreamsConfig.UpstreamKind = XyzStreamsConfig.UpstreamKind.SLING_247V2,
        ftvPath: String? = null,
    ) = XyzStreamsConfig.ChannelRow(
        streamId = streamId,
        displayName = displayName,
        tvgId = tvgId,
        logo = logo,
        epgKeys = epgKeys,
        groupTitle = groupTitle,
        upstreamKind = upstreamKind,
        ftvPath = ftvPath,
    )

    private fun coreUsChannels(): List<XyzStreamsConfig.ChannelRow> = listOf(
        sling("cbs", "CBS", "WCBS.us", setOf("WCBS-DT", "CBS", "CBSSPTHD"), GroupTitleResolver.LOCAL_CHANNELS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/b/bd/CBS_Eyemark.svg",
            upstreamKind = XyzStreamsConfig.UpstreamKind.FTV_LOCAL, ftvPath = "WCBS/F0001/master.m3u8"),
        sling("abcwabc", "ABC", "WABC.us", setOf("WABC-DT", "ABC"), GroupTitleResolver.LOCAL_CHANNELS,
            logo = "https://www.tvguide.com/a/img/resize/8a2d3ef18c67ea9ab8e6704e47094c64106e0106/catalog/provider/8/4/8-9200000057_light.png?fit=crop&height=64&width=64"),
        sling("foxwnyw", "FOX", "WNYW.us", setOf("WNYW-DT", "FOX"), GroupTitleResolver.LOCAL_CHANNELS,
            logo = "https://www.tvguide.com/a/img/resize/630e91af9522ad53b2cbdf93445373fb77375f90/catalog/provider/8/4/8-9233003497_light.png?fit=crop&height=64&width=64"),
        sling("nbcwnbc", "NBC", "WNBC.us", setOf("WNBC-DT", "NBC"), GroupTitleResolver.LOCAL_CHANNELS,
            logo = "https://www.tvguide.com/a/img/resize/58c8d48e9a1a8f719af801cc3bccb8ea9005bc69/catalog/provider/8/4/8-9200016000_light.png?fit=crop&height=64&width=64"),
        sling("espn", "ESPN", "ESPN.us", setOf("ESPNHD", "ESPN"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2f/ESPN_wordmark.svg/1280px-ESPN_wordmark.svg.png"),
        sling("espn2", "ESPN2", "ESPN2.us", setOf("ESPN2D", "ESPN2"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bf/ESPN2_logo.svg/960px-ESPN2_logo.svg.png"),
        sling("espnu", "ESPNU", "ESPNU.us", setOf("ESPNUHD", "ESPNU"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/ca/ESPN_U_logo.svg/960px-ESPN_U_logo.svg.png"),
        sling("espnews", "ESPNEWS", "ESPNews.us", setOf("ESPNNEWHD", "ESPNEWS"), GroupTitleResolver.SPORTS,
            logo = "https://logos-world.net/wp-content/uploads/2022/06/ESPNews-Logo.png"),
        sling("tnt", "TNT", "TNT.us", setOf("TNTHD", "TNT"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/2/24/TNT_Logo_2016.svg"),
        sling("tbs", "TBS", "TBS.us", setOf("TBSHD", "TBS"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/d/de/TBS_logo_2016.svg"),
        sling("usa", "USA Network", "USA.us", setOf("USAHD", "USA"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/84/USA_Network_2020.svg/1280px-USA_Network_2020.svg.png"),
        sling("foxsports1", "FS1", "FS1.us", setOf("FS1HD", "FS1"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/37/2015_Fox_Sports_1_logo.svg/1280px-2015_Fox_Sports_1_logo.svg.png"),
        sling("foxsports2", "FS2", "FS2.us", setOf("FS2HD", "FS2"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/38/FS2_logo_2015.svg/1280px-FS2_logo_2015.svg.png"),
        sling("amc", "AMC", "AMC.us", setOf("AMC"), GroupTitleResolver.MOVIES,
            logo = "https://upload.wikimedia.org/wikipedia/commons/3/34/AMC_logo_2019.svg"),
        sling("ae", "A&E", "AandE.us", setOf("A&E"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/d/df/A%26E_Network_logo.svg/960px-A%26E_Network_logo.svg.png"),
        sling("cartoonnetworkadultswim", "Cartoon Network", "CartoonNetwork.us", setOf("TOONHD", "Cartoon Network"), GroupTitleResolver.KIDS,
            logo = "https://cdn.mos.cms.futurecdn.net/dmfiXjgnqaVnSWfFAW6KgT.png"),
        sling("nickelodeondish", "Nickelodeon", "Nickelodeon.us", setOf("NICKHD", "Nickelodeon"), GroupTitleResolver.KIDS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/7/71/Nickelodeon_2023_logo.svg"),
        sling("disneyxd", "Disney XD", "DisneyXD.us", setOf("DISNEYXDHD", "Disney XD"), GroupTitleResolver.KIDS),
        sling("disneychannel", "Disney Channel", "DisneyChannel.us", setOf("DISNEYHD", "Disney Channel"), GroupTitleResolver.KIDS,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6c/2014_Disney_Channel_logo.svg/500px-2014_Disney_Channel_logo.svg.png"),
        sling("bravo", "Bravo", "Bravo.us", setOf("BRAVOHD", "Bravo"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/22/Bravo_2024.svg/1280px-Bravo_2024.svg.png"),
        sling("tlc", "TLC", "TLC.us", setOf("TLC"), GroupTitleResolver.ENTERTAINMENT,
            logo = "https://upload.wikimedia.org/wikipedia/commons/a/af/TLC-Logo_2016.png"),
        sling("travelchannel", "Travel Channel", "TravelChannel.us", setOf("TRAVELHD", "Travel Channel"), GroupTitleResolver.DOCUMENTARY,
            logo = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8a/2018_Travel_Channel_logo.svg/1280px-2018_Travel_Channel_logo.svg.png"),
        sling("axs", "AXS TV", "AXSTV.us", setOf("AXSTV", "AXS TV"), GroupTitleResolver.MUSIC,
            logo = "https://upload.wikimedia.org/wikipedia/commons/a/a7/Axs_logo.svg"),
        sling("investigationdiscovery", "Investigation Discovery", "InvestigationDiscovery.us", setOf("INVSTDSCHD", "Investigation Discovery"), GroupTitleResolver.DOCUMENTARY),
        sling("discoveryturbo", "Discovery Turbo", "DiscoveryTurbo.us", setOf("MTTREND", "Discovery Turbo"), GroupTitleResolver.DOCUMENTARY),
        sling("nflnetwork", "NFL Network", "NFLNetwork.us", setOf("NFLHD", "NFL Network"), GroupTitleResolver.SPORTS,
            logo = "https://upload.wikimedia.org/wikipedia/en/thumb/8/8f/NFL_Network_logo.svg/1280px-NFL_Network_logo.svg.png"),
    )

    private fun expandedVerifiedChannels(): List<XyzStreamsConfig.ChannelRow> = listOf(
        sling("cnn", "CNN", "CNN.us", setOf("CNNHD", "CNN"), GroupTitleResolver.NEWS),
        sling("hln", "HLN", "HLN.us", setOf("HLNHD", "HLN"), GroupTitleResolver.NEWS),
        sling("cnbc", "CNBC", "CNBC.us", setOf("CNBCHD", "CNBC"), GroupTitleResolver.NEWS),
        sling("foxnews", "Fox News", "FoxNews.us", setOf("FNCHD"), GroupTitleResolver.NEWS),
        sling("newsmax", "Newsmax", "Newsmax.us", setOf("NEWSMAXHD"), GroupTitleResolver.NEWS),
        sling("bbcamerica", "BBC America", "BBCAmerica.us", setOf("BBCAMHD"), GroupTitleResolver.ENTERTAINMENT),
        sling("mlbnetwork", "MLB Network", "MLBNetwork.us", setOf("MLBHD"), GroupTitleResolver.SPORTS),
        sling("nbatv", "NBA TV", "NBATV.us", setOf("NBAHD"), GroupTitleResolver.SPORTS),
        sling("nhlnetwork", "NHL Network", "NHLNetwork.us", setOf("NHLTVHD"), GroupTitleResolver.SPORTS),
        sling("golfchannel", "Golf Channel", "GolfChannel.us", setOf("GOLFHD"), GroupTitleResolver.SPORTS),
        sling("tennischannel", "Tennis Channel", "TennisChannel.us", setOf("TENNISHD"), GroupTitleResolver.SPORTS),
        sling("accnetwork", "ACC Network", "ACCNetwork.us", setOf("ACCNHD"), GroupTitleResolver.SPORTS),
        sling("secnetwork", "SEC Network", "SECNetwork.us", setOf("SECNETHD"), GroupTitleResolver.SPORTS),
        sling("beinsports", "beIN Sports", "beINSports.us", setOf("BEINXTRA"), GroupTitleResolver.SPORTS),
        sling("hgtv", "HGTV", "HGTV.us", setOf("HGTVHD", "HGTV"), GroupTitleResolver.ENTERTAINMENT),
        sling("foodnetwork", "Food Network", "FoodNetwork.us", setOf("FOODHD", "FOOD"), GroupTitleResolver.ENTERTAINMENT),
        sling("discovery", "Discovery Channel", "DiscoveryChannel.us", setOf("TDC-HD", "DSC", "ANIMALHD"), GroupTitleResolver.DOCUMENTARY),
        sling("history", "History", "History.us", setOf("HIST", "THCSP"), GroupTitleResolver.DOCUMENTARY),
        sling("comedycentral", "Comedy Central", "ComedyCentral.us", setOf("COMEDYHD"), GroupTitleResolver.ENTERTAINMENT),
        sling("mtv", "MTV", "MTV.us", setOf("MTVHD", "MTV"), GroupTitleResolver.ENTERTAINMENT),
        sling("vh1", "VH1", "VH1.us", setOf("VH1HD", "VH1"), GroupTitleResolver.ENTERTAINMENT),
        sling("bet", "BET", "BET.us", setOf("BETHD", "BET"), GroupTitleResolver.ENTERTAINMENT),
        sling("e", "E!", "EEntertainment.us", setOf("ETV-HD", "E!"), GroupTitleResolver.ENTERTAINMENT),
        sling("fx", "FX", "FX.us", setOf("FXHD", "FX"), GroupTitleResolver.ENTERTAINMENT),
        sling("fxx", "FXX", "FXX.us", setOf("FXXHD", "FXX"), GroupTitleResolver.ENTERTAINMENT),
        sling("syfy", "Syfy", "Syfy.us", setOf("SYFYHD", "SyFyHD"), GroupTitleResolver.ENTERTAINMENT),
        sling("hallmarkchannel", "Hallmark Channel", "HallmarkChannel.us", setOf("HALLMARKHD"), GroupTitleResolver.MOVIES),
        sling("lifetime", "Lifetime", "Lifetime.us", setOf("LIFEHD", "LIFE"), GroupTitleResolver.ENTERTAINMENT),
        sling("ion", "ION", "ION.us", setOf("IONHD", "ION"), GroupTitleResolver.ENTERTAINMENT),
        sling("disneyjr", "Disney Junior", "DisneyJunior.us", setOf("DISNEYJRHD"), GroupTitleResolver.KIDS),
        sling("nickjr", "Nick Jr.", "NickJr.us", setOf("NICKJRHD"), GroupTitleResolver.KIDS),
        sling("hbomax", "HBO Max", "HBOMax.us", setOf("HBOHD", "HBO", "MAXHD", "MAX"), GroupTitleResolver.MOVIES),
        sling("starz", "Starz", "Starz.us", setOf("STARZHD"), GroupTitleResolver.MOVIES),
        sling("cinemax", "Cinemax", "Cinemax.us", setOf("CINEMAXHD", "5STARMAXHD"), GroupTitleResolver.MOVIES),
        sling("mgm", "MGM+", "MGMPlus.us", setOf("MGM+", "MGM+HIT", "MGM+MAR"), GroupTitleResolver.MOVIES),
        sling("trutv", "TruTV", "TruTV.us", setOf("TRUTVHD"), GroupTitleResolver.ENTERTAINMENT),
        sling("tvland", "TV Land", "TVLand.us", setOf("TVLANDHD", "TVLAND"), GroupTitleResolver.ENTERTAINMENT),
        sling("natgeowild", "Nat Geo Wild", "NatGeoWild.us", setOf("NGEOWILDHD"), GroupTitleResolver.DOCUMENTARY),
        sling("foxweather", "Fox Weather", "FoxWeather.us", setOf("FOXWTHR"), GroupTitleResolver.NEWS),
        sling("paramountnetwork", "Paramount Network", "ParamountNetwork.us", setOf("PARAMOUNT"), GroupTitleResolver.ENTERTAINMENT),
        sling("tele", "Telemundo", "Telemundo.us", setOf("TELEMUNDHD", "TELMUN", "WNJU-DT"), GroupTitleResolver.EN_ESPANOL),
        sling("universo", "Universo", "Universo.us", setOf("UNIMAS", "UNI"), GroupTitleResolver.EN_ESPANOL),
    )
}
