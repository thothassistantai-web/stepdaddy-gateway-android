package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementImportModeMigrationTest {
    @Test
    fun `null stored mode migrates when not user-set`() {
        assertTrue(SupplementImportModeMigration.shouldMigrateToFullCatalog(null, userSet = false))
    }

    @Test
    fun `CONSOLIDATE migrates when not user-set`() {
        assertTrue(
            SupplementImportModeMigration.shouldMigrateToFullCatalog("CONSOLIDATE_FALLBACKS", userSet = false),
        )
        assertTrue(
            SupplementImportModeMigration.shouldMigrateToFullCatalog("consolidate_fallbacks", userSet = false),
        )
        assertTrue(SupplementImportModeMigration.shouldMigrateToFullCatalog("", userSet = false))
    }

    @Test
    fun `user-set CONSOLIDATE is respected`() {
        assertFalse(
            SupplementImportModeMigration.shouldMigrateToFullCatalog("CONSOLIDATE_FALLBACKS", userSet = true),
        )
    }

    @Test
    fun `user-set FULL_CATALOG is respected`() {
        assertFalse(SupplementImportModeMigration.shouldMigrateToFullCatalog("FULL_CATALOG", userSet = true))
    }

    @Test
    fun `skip and explicit full catalog tokens are left alone when stored`() {
        assertFalse(SupplementImportModeMigration.shouldMigrateToFullCatalog("SKIP_DUPLICATES", userSet = false))
        assertFalse(SupplementImportModeMigration.shouldMigrateToFullCatalog("FULL_CATALOG", userSet = false))
    }

    @Test
    fun `fromPref empty uses full catalog default`() {
        assertEquals(SupplementImportMode.FULL_CATALOG, SupplementImportMode.fromPref(null))
        assertEquals(SupplementImportMode.FULL_CATALOG, SupplementImportMode.fromPref(""))
        assertEquals(
            SupplementImportMode.CONSOLIDATE_FALLBACKS,
            SupplementImportMode.fromPref("CONSOLIDATE_FALLBACKS"),
        )
    }

    @Test
    fun `target mode is full catalog`() {
        assertEquals(SupplementImportMode.FULL_CATALOG, SupplementImportModeMigration.targetMode())
    }

    @Test
    fun `defaults version bumped for full catalog flip`() {
        assertEquals(3, SupplementImportModeMigration.DEFAULTS_VERSION)
    }
}
