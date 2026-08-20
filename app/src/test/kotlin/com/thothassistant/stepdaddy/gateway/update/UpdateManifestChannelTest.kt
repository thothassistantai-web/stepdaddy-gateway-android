package com.thothassistant.stepdaddy.gateway.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestChannelTest {
    private val manifest = UpdateManifest(
        versionCode = 30028,
        versionName = "3.0.28",
        apkUrl = "https://example.com/release.apk",
        apkUrlDebug = "https://example.com/debug.apk",
        apkSha256 = "aaa",
        apkSha256Debug = "bbb",
    )

    @Test
    fun forCurrentBuild_keepsReleaseUrlForGraduation() {
        val debugView = manifest.forCurrentBuild(isDebugBuild = true)
        assertNotNull(debugView)
        assertEquals("https://example.com/release.apk", debugView!!.releaseApkUrl())
        assertEquals("https://example.com/debug.apk", debugView.resolvedApkUrl(isDebugBuild = true))
        assertEquals("bbb", debugView.resolvedApkSha256(isDebugBuild = true))
    }

    @Test
    fun releaseBuild_usesReleaseChannel() {
        val releaseView = manifest.forCurrentBuild(isDebugBuild = false)
        assertNotNull(releaseView)
        assertEquals("https://example.com/release.apk", releaseView!!.resolvedApkUrl(false))
        assertEquals("aaa", releaseView.resolvedApkSha256(false))
    }

    @Test
    fun blankUrls_rejected() {
        assertNull(
            UpdateManifest(
                versionCode = 1,
                versionName = "1",
                apkUrl = "  ",
                apkUrlDebug = null,
            ).forCurrentBuild(false),
        )
    }
}
