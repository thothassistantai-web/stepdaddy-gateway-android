package com.thothassistant.stepdaddy.gateway.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchVersionComparatorTest {
    @Test
    fun versionCodeFromPatchName_parsesSemanticTriple() {
        assertEquals(10201, PatchVersionComparator.versionCodeFromPatchName("1.2.1-boot-tune-safe"))
        assertEquals(10200, PatchVersionComparator.versionCodeFromPatchName("1.2.0-boot-fast"))
    }

    @Test
    fun isUpdateAvailable_usesVersionCodeWhenPresent() {
        assertTrue(
            PatchVersionComparator.isUpdateAvailable(
                installedPatchVersion = "1.2.0-boot-fast",
                installedVersionCode = null,
                latestPatchVersion = "1.2.1-boot-tune-safe",
                latestVersionCode = 10201,
            ),
        )
        assertFalse(
            PatchVersionComparator.isUpdateAvailable(
                installedPatchVersion = "1.2.1-boot-tune-safe",
                installedVersionCode = 10201,
                latestPatchVersion = "1.2.1-boot-tune-safe",
                latestVersionCode = 10201,
            ),
        )
    }

    @Test
    fun compare_fallsBackToNormalizedName() {
        assertTrue(
            PatchVersionComparator.compare(
                installedPatchVersion = "1.2.0-boot-fast",
                installedVersionCode = null,
                latestPatchVersion = "1.2.1-boot-tune-safe",
                latestVersionCode = 0,
            ) > 0,
        )
    }
}
