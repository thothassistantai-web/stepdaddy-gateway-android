package com.thothassistant.stepdaddy.gateway.upstream

/**
 * Country ordering within a category: US, then CA, then UK, then other English, then rest.
 * [PlaylistBuilder] still sorts All Channels strictly by [tvg-chno].
 */
object ChannelCountrySort {
    /** English-speaking territories after US / CA / UK. */
    private val OTHER_ENGLISH_CODES = setOf(
        "IE",
        "AU",
        "NZ",
        "ZA",
        "SG",
        "JM",
        "TT",
        "BS",
        "BB",
        "MT",
    )

    fun normalizeCode(countryCode: String): String =
        when (countryCode.trim().uppercase()) {
            "GB" -> "UK"
            else -> countryCode.trim().uppercase()
        }

    /** Lexicographic key — lower sorts earlier within the same category. */
    fun prioritySortKey(countryCode: String): String {
        val code = normalizeCode(countryCode)
        return when (code) {
            "US" -> "0"
            "CA" -> "1"
            "UK" -> "2"
            "" -> "9"
            in OTHER_ENGLISH_CODES -> "3$code"
            else -> "4$code"
        }
    }

    fun compareCountryCodes(left: String, right: String): Int =
        prioritySortKey(left).compareTo(prioritySortKey(right))
}
