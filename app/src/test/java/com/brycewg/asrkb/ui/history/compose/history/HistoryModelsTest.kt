package com.brycewg.asrkb.ui.history.compose.history

import com.brycewg.asrkb.store.AsrHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryModelsTest {
    @Test
    fun searchMatchesRawTextAsWellAsFinalText() {
        val record = AsrHistoryStore.AsrHistoryRecord(
            id = "raw",
            timestamp = 1_000,
            text = "processed result",
            rawText = "original transcript",
            vendorId = "volc",
            audioMs = 1,
            source = "ime",
            aiProcessed = false,
            charCount = 16
        )

        val filtered = filterHistoryRecords(
            records = listOf(record),
            query = "original",
            filterState = HistoryFilterState(),
            now = 1_000
        )

        assertEquals(listOf(record), filtered)
    }
}
