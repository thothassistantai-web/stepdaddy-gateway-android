package com.thothassistant.stepdaddy.gateway.upstream

enum class PlaylistTitleStyle {
    /** `{Name} {flag} {CC}` suffix (legacy StepDaddy). */
    LEGACY,

    /** `{CC}: {NAME} HD` cable / `{CC}: {NAME} ᴿᴬᵂ` FAST — category groups unchanged. */
    XTREAM_CATEGORY,
    ;

    companion object {
        fun fromPref(raw: String?): PlaylistTitleStyle =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: XTREAM_CATEGORY
    }
}

enum class PlaylistTitleSource {
    CABLE,
    FAST,
    SIDECAR,
    /** Movies / series VOD — keep Xtream-style `Title (Year)` / `Show - S01E01` as-is. */
    VOD,
    ADULT,
    ADULT_SWIM_247,
    SPECIAL_EVENT,
    SPECIAL_EVENT_GUIDE,
}
