package com.thothassistant.stepdaddy.gateway.network

enum class NetworkAccessMode {
    DEFAULT,
    LOCAL,
    REMOTE,
    ;

    companion object {
        fun fromPref(value: String?): NetworkAccessMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
