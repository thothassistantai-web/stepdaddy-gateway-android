package com.thothassistant.stepdaddy.gateway.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun manifest(
        mandatory: Boolean = false,
        updateType: String? = null,
        minVersionCode: Int? = null,
        minSupportedVersionCode: Int? = null,
        title: String? = null,
        message: String? = null,
        releaseNotes: String? = null,
    ) = UpdateManifest(
        versionCode = 30038,
        versionName = "3.0.38",
        apkUrl = "https://example.com/release.apk",
        mandatory = mandatory,
        updateType = updateType,
        minVersionCode = minVersionCode,
        minSupportedVersionCode = minSupportedVersionCode,
        title = title,
        message = message,
        releaseNotes = releaseNotes,
    )

    @Test
    fun optionalByDefault() {
        assertFalse(UpdatePolicy.isMandatory(manifest(), installedVersionCode = 30037))
    }

    @Test
    fun mandatoryBoolean() {
        assertTrue(UpdatePolicy.isMandatory(manifest(mandatory = true), installedVersionCode = 30037))
    }

    @Test
    fun updateTypeMandatory() {
        assertTrue(
            UpdatePolicy.isMandatory(
                manifest(updateType = "mandatory"),
                installedVersionCode = 30037,
            ),
        )
        assertTrue(
            UpdatePolicy.isMandatory(
                manifest(updateType = "MANDATORY"),
                installedVersionCode = 30037,
            ),
        )
    }

    @Test
    fun updateTypeOptionalExplicit() {
        assertFalse(
            UpdatePolicy.isMandatory(
                manifest(updateType = "optional"),
                installedVersionCode = 30037,
            ),
        )
    }

    @Test
    fun minSupportedForcesMandatory() {
        assertTrue(
            UpdatePolicy.isMandatory(
                manifest(updateType = "optional", minSupportedVersionCode = 30038),
                installedVersionCode = 30037,
            ),
        )
        assertFalse(
            UpdatePolicy.isMandatory(
                manifest(updateType = "optional", minSupportedVersionCode = 30038),
                installedVersionCode = 30038,
            ),
        )
    }

    @Test
    fun legacyMinVersionCodeAlias() {
        assertTrue(
            UpdatePolicy.isMandatory(
                manifest(minVersionCode = 30040),
                installedVersionCode = 30037,
            ),
        )
        assertEquals(
            30040,
            UpdatePolicy.effectiveMinSupportedVersionCode(manifest(minVersionCode = 30040)),
        )
    }

    @Test
    fun minSupportedPreferredOverLegacy() {
        assertEquals(
            30050,
            UpdatePolicy.effectiveMinSupportedVersionCode(
                manifest(minVersionCode = 30040, minSupportedVersionCode = 30050),
            ),
        )
    }

    @Test
    fun dialogOverrides() {
        val m = manifest(title = " Emergency ", message = " Please update ", releaseNotes = "notes")
        assertEquals("Emergency", UpdatePolicy.dialogTitle(m))
        assertEquals("Please update", UpdatePolicy.dialogMessage(m))
        assertNull(UpdatePolicy.dialogTitle(manifest()))
        assertNull(UpdatePolicy.dialogMessage(manifest(releaseNotes = "only notes")))
    }
}
