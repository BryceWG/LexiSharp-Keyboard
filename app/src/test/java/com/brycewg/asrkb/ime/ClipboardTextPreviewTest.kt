package com.brycewg.asrkb.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardTextPreviewTest {
    @Test
    fun preview_isBoundedAndSingleLine() {
        val text = "ab\ncd" + "x".repeat(1_000_000)

        assertEquals("ab cdxxx", clipboardTextPreview(text, maxChars = 8))
    }

    @Test
    fun preview_handlesNonPositiveLimit() {
        assertEquals("", clipboardTextPreview("content", maxChars = 0))
    }
}
