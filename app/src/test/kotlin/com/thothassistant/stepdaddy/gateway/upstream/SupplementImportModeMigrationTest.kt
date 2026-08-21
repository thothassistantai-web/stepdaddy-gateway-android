package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementImportModeMigrationTest {
    @Test
    fun `null stored mode migrates when not user-set`() {
        assertTrue(SupplementImportModeMigration.shouldMigrateToConsolidate(null, userSet = false))
    }

    @Test
    fun `FULL_CATALOG migrates when not user-set`() {
        assertTrue(SupplementImportModeMigration.shouldMigrateToConsolidate("FULL_CATALOG", userSet = false))
        assertTrue(SupplementImportModeMigration.shouldMigrateToConsolidate("full_catalog", userSet = false))
        assertTrue(SupplementImportModeMigration.shouldMigrateToConsolidate("ALL", userSet = false))
    }

    @Test
    fun `user-set FULL_CATALOG is respected`() {
        assertFalse(SupplementImportModeMigration.shouldMigrateToConsolidate("FULL_CATALOG", userSet = true))
    }

    @Test
    fun `skip and consolidate are left alone`() {
        assertFalse(SupplementImportModeMigration.shouldMigrateToConsolidate("SKIP_DUPLICATES", userSet = false))
        assertFalse(
            SupplementImportModeMigration.shouldMigrateToConsolidate("CONSOLIDATE_FALLBACKS", userSet = false),
        )
    }

    @Test
    fun `fromPref empty uses consolidate default`() {
        assertEquals(SupplementImportMode.CONSOLIDATE_FALLBACKS, SupplementImportMode.fromPref(null))
        assertEquals(SupplementImportMode.CONSOLIDATE_FALLBACKS, SupplementImportMode.fromPref(""))
        assertEquals(SupplementImportMode.FULL_CATALOG, SupplementImportMode.fromPref("FULL_CATALOG"))
    }

    @Test
    fun `target mode is consolidate`() {
        assertEquals(SupplementImportMode.CONSOLIDATE_FALLBACKS, SupplementImportModeMigration.targetMode())
    }
}
