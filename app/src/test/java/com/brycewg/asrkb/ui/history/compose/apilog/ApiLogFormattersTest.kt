package com.brycewg.asrkb.ui.history.compose.apilog

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiLogFormattersTest {
    @Test
    fun formatsCohereVendorName() {
        assertEquals("Cohere", formatApiLogVendorName("cohere"))
    }
}
