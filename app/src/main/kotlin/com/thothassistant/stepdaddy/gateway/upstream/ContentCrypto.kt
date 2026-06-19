package com.thothassistant.stepdaddy.gateway.upstream

import java.security.MessageDigest
import java.util.Base64

object ContentCrypto {
    private val keyBytes: ByteArray by lazy {
        val secret = "change-me-now"
        MessageDigest.getInstance("SHA-256")
            .digest("stepdaddy-content:$secret".toByteArray(Charsets.UTF_8))
    }

    fun encrypt(input: String): String {
        val inputBytes = input.toByteArray(Charsets.UTF_8)
        val xored = xor(inputBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(xored)
    }

    fun decrypt(input: String): String {
        var padded = input
        val mod = padded.length % 4
        if (mod != 0) {
            padded += "=".repeat(4 - mod)
        }
        val decoded = Base64.getUrlDecoder().decode(padded)
        return String(xor(decoded), Charsets.UTF_8)
    }

    private fun xor(inputBytes: ByteArray): ByteArray {
        val out = ByteArray(inputBytes.size)
        for (i in inputBytes.indices) {
            out[i] = (inputBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return out
    }
}
