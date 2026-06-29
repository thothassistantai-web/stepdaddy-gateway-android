package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamCategoryTitleFormatterTest {
    private fun usResolution() = GroupTitleResolver.Resolution(
        groupTitle = GroupTitleResolver.NEWS,
        categoryLabel = GroupTitleResolver.NEWS,
        countryCode = "US",
        flagEmoji = "🇺🇸",
        isAdult = false,
        appendCountrySuffix = true,
    )

    @Test
    fun format_cableChannel_usesCountryPrefixAndHd() {
        val title = XtreamCategoryTitleFormatter.format(
            channelName = "Fox News Channel",
            resolution = usResolution(),
            source = PlaylistTitleSource.CABLE,
        )
        assertEquals("US: FOX NEWS CHANNEL HD", title)
    }

    @Test
    fun format_stripsLegacyCountrySuffixAndProviderTag() {
        val title = XtreamCategoryTitleFormatter.format(
            channelName = "Lifetime Movies Love & Drama (1080p) 🇺🇸 US Samsung",
            resolution = usResolution().copy(
                groupTitle = GroupTitleResolver.ENTERTAINMENT,
                categoryLabel = GroupTitleResolver.ENTERTAINMENT,
            ),
            source = PlaylistTitleSource.FAST,
        )
        assertEquals("US: LIFETIME MOVIES LOVE & DRAMA ᴿᴬᵂ", title)
    }

    @Test
    fun format_ukSports_usesUkPrefix() {
        val title = XtreamCategoryTitleFormatter.format(
            channelName = "Sky Sports Main Event UK",
            resolution = GroupTitleResolver.Resolution(
                groupTitle = GroupTitleResolver.SPORTS,
                categoryLabel = GroupTitleResolver.SPORTS,
                countryCode = "UK",
                flagEmoji = "🇬🇧",
                isAdult = false,
                appendCountrySuffix = true,
            ),
            source = PlaylistTitleSource.CABLE,
        )
        assertEquals("UK: SKY SPORTS MAIN EVENT HD", title)
    }

    @Test
    fun format_lifetimeNetwork_cableHd() {
        val title = XtreamCategoryTitleFormatter.format(
            channelName = "Lifetime Network",
            resolution = usResolution().copy(
                groupTitle = GroupTitleResolver.ENTERTAINMENT,
                categoryLabel = GroupTitleResolver.ENTERTAINMENT,
            ),
            source = PlaylistTitleSource.CABLE,
        )
        assertEquals("US: LIFETIME NETWORK HD", title)
    }

    @Test
    fun formatAdultSwimMarathon_uses247Prefix() {
        val title = XtreamCategoryTitleFormatter.formatAdultSwimMarathon("Rick and Morty")
        assertEquals("US: 24/7 : Adultswim RICK AND MORTY ᴿᴬᵂ", title)
    }

    @Test
    fun formatSpecialEvent_includesLeagueAndLiveSuffix() {
        val title = XtreamCategoryTitleFormatter.formatSpecialEvent("Lakers vs Celtics", "NBA")
        assertEquals("US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ", title)
    }

    @Test
    fun formatSpecialEvent_ukRegion_usesUkPrefix() {
        val title = XtreamCategoryTitleFormatter.formatSpecialEvent(
            "Arsenal vs Chelsea",
            "SOCCER",
            countryCode = "UK",
        )
        assertEquals("UK: SOCCER ARSENAL VS CHELSEA ᴸᴵⱽᴱ", title)
    }

    @Test
    fun formatSpecialEvent_endedGracePrefixesRedDot() {
        val title = XtreamCategoryTitleFormatter.formatSpecialEvent("Lakers vs Celtics", "NBA", endedGrace = true)
        assertEquals("🔴 US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ", title)
    }
}
