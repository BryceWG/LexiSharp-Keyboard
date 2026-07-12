package com.brycewg.asrkb.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardHistoryStoreTest {
    @Test
    fun normalizeClipboardHistoryText_trimsAndLimitsStoredText() {
        val oversized = "  " + "a".repeat(ClipboardHistoryStore.MAX_STORED_TEXT_CHARS + 100) + "  "

        val normalized = normalizeClipboardHistoryText(oversized)

        assertEquals(ClipboardHistoryStore.MAX_STORED_TEXT_CHARS, normalized.length)
        assertEquals("a", normalized.first().toString())
        assertEquals("a", normalized.last().toString())
    }

    @Test
    fun normalizeClipboardHistoryText_preservesNormalContent() {
        assertEquals("hello\nworld", normalizeClipboardHistoryText("  hello\nworld  "))
    }
}
