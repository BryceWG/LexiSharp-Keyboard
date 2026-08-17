package com.brycewg.asrkb.ui.settings.compose.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibiSettingsRouteTest {
    @Test
    fun paywallRouteHasStableId() {
        assertEquals(BibiSettingsRoute.Paywall, BibiSettingsRoute.fromId("paywall"))
    }

    @Test
    fun unknownRouteIsIgnored() {
        assertNull(BibiSettingsRoute.fromId("not-a-route"))
    }
}
