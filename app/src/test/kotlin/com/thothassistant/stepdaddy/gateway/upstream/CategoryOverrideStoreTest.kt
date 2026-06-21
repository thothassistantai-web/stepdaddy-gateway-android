package com.thothassistant.stepdaddy.gateway.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOverrideStoreTest {
    @Test
    fun `valid groups include Movies and Sports`() {
        assertEquals(true, GroupTitleResolver.MOVIES in CategoryOverrideStore.validGroups)
        assertEquals(true, GroupTitleResolver.SPECIAL_EVENTS in CategoryOverrideStore.validGroups)
    }
}
