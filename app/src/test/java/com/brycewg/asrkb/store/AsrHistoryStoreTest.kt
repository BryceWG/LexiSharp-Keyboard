package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrHistoryStoreTest {
    private lateinit var context: Context
    private lateinit var store: AsrHistoryStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit()
            .remove(KEY_ASR_HISTORY_JSON)
            .remove(KEY_AUDIO_HISTORY_RETENTION_COUNT)
            .commit()
        store = AsrHistoryStore(context)
    }

    @After
    fun tearDown() {
        store.clearAll()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit()
            .remove(KEY_AUDIO_HISTORY_RETENTION_COUNT)
            .commit()
    }

    @Test
    fun oldJsonWithoutRawTextRemainsReadable() {
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit()
            .putString(
                "asr_history",
                """[{"id":"old","timestamp":1,"text":"final","vendorId":"volc","audioMs":2,"source":"ime","aiProcessed":false,"charCount":5}]"""
            )
            .commit()

        val record = store.listAll().single()

        assertEquals("final", record.text)
        assertNull(record.rawText)
    }

    @Test
    fun updateByIdPreservesIdentityAndOtherRecords() {
        store.add(record("one", "raw one", "final one", 1))
        store.add(record("two", "raw two", "final two", 2))

        val updated = store.updateById("one") { it.copy(text = "changed") }

        assertEquals("one", updated?.id)
        assertEquals("changed", store.listAll().first { it.id == "one" }.text)
        assertEquals("final two", store.listAll().first { it.id == "two" }.text)
    }

    @Test
    fun duplicateIdsAreCollapsedAndNewAddsReplaceTheOldRecord() {
        store.add(record("same", "old raw", "old", 1))
        store.add(record("same", "new raw", "new", 2))

        val records = store.listAll()

        assertEquals(1, records.size)
        assertEquals("new", records.single().text)
    }

    @Test
    fun legacyJsonWithDuplicateIdsDoesNotExposeDuplicateRecords() {
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit()
            .putString(
                "asr_history",
                """
                    [
                      {"id":"same","timestamp":2,"text":"new","vendorId":"volc","audioMs":1,"source":"ime","aiProcessed":false,"charCount":3},
                      {"id":"same","timestamp":1,"text":"old","vendorId":"volc","audioMs":1,"source":"ime","aiProcessed":false,"charCount":3}
                    ]
                """.trimIndent()
            )
            .commit()

        val records = store.listAll()

        assertEquals(1, records.size)
        assertEquals("new", records.single().text)
    }

    @Test
    fun audioRetentionDefaultsAndClamps() {
        val prefs = Prefs(context)
        assertEquals(10, prefs.audioHistoryRetentionCount)
        prefs.audioHistoryRetentionCount = -1
        assertEquals(0, prefs.audioHistoryRetentionCount)
        prefs.audioHistoryRetentionCount = 101
        assertEquals(100, prefs.audioHistoryRetentionCount)
    }

    private fun record(id: String, raw: String, final: String, timestamp: Long) =
        AsrHistoryStore.AsrHistoryRecord(
            id = id,
            timestamp = timestamp,
            text = final,
            rawText = raw,
            vendorId = "volc",
            audioMs = 1,
            source = "ime",
            aiProcessed = false,
            charCount = final.length
        )
}
