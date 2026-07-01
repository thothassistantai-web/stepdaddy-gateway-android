package com.thothassistant.stepdaddy.gateway.xtream

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Xtream Codes login defaults and URL helpers for TiviMate auto-import. */
object XtreamCredentials {
    const val DEFAULT_USERNAME = "admin"
    const val DEFAULT_PASSWORD = "password"
    const val GET_PHP_TYPE = "m3u_plus"
    const val GET_PHP_OUTPUT = "ts"

    /** Full get.php URL TiviMate recognizes as an Xtream playlist import. */
    fun getPhpImportUrl(baseUrl: String, username: String, password: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val encUser = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8.name())
        val encPass = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
        return "$base/get.php?username=$encUser&password=$encPass&type=$GET_PHP_TYPE&output=$GET_PHP_OUTPUT"
    }

    /** Mirrors TiviMate's Xtream URL detector (requires all four query parts). */
    fun isXtreamImportUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.contains("/get.php?username=") &&
            trimmed.contains("&password=") &&
            trimmed.contains("&type=") &&
            trimmed.contains("&output=")
    }
}
