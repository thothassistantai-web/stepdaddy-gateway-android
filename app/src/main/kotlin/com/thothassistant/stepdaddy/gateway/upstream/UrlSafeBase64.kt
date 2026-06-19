package com.thothassistant.stepdaddy.gateway.upstream

import java.util.Base64

object UrlSafeBase64 {
    fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String): String {
        var padded = value
        val mod = padded.length % 4
        if (mod != 0) {
            padded += "=".repeat(4 - mod)
        }
        return String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }
}
