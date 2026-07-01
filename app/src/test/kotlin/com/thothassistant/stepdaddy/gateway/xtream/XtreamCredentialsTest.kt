package com.thothassistant.stepdaddy.gateway.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamCredentialsTest {
    @Test
    fun getPhpImportUrl_includesRequiredQueryParts() {
        val url = XtreamCredentials.getPhpImportUrl(
            "http://127.0.0.1:3000",
            "admin",
            "password",
        )
        assertTrue(url.contains("/get.php?username=admin"))
        assertTrue(url.contains("&password=password"))
        assertTrue(url.contains("&type=m3u_plus"))
        assertTrue(url.contains("&output=ts"))
        assertTrue(XtreamCredentials.isXtreamImportUrl(url))
    }

    @Test
    fun isXtreamImportUrl_rejectsPlainM3u() {
        assertFalse(
            XtreamCredentials.isXtreamImportUrl("http://127.0.0.1:3000/tivimate.m3u"),
        )
    }

    @Test
    fun getPhpImportUrl_encodesSpecialCharacters() {
        val url = XtreamCredentials.getPhpImportUrl(
            "http://127.0.0.1:3000",
            "user@host",
            "p@ss w/rd",
        )
        assertEquals(
            "http://127.0.0.1:3000/get.php?username=user%40host&password=p%40ss+w%2Frd" +
                "&type=m3u_plus&output=ts",
            url,
        )
    }
}
