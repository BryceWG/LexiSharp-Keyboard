package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrHistoryAudioStoreTest {
    private lateinit var context: Context
    private lateinit var store: AsrHistoryAudioStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AsrHistoryAudioStore(context)
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
        Prefs(context).apply {
            disableAsrHistory = false
            audioHistoryRetentionCount = 10
        }
    }

    @Test
    fun saveReadAndDeleteRoundTrip() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertTrue(store.save("one", pcm))
        assertArrayEquals(pcm, store.readAudio("one"))
        store.delete("one")
        assertFalse(store.hasAudio("one"))
    }

    @Test
    fun pruneKeepsNewestAvailableRecords() {
        store.save("old", byteArrayOf(1, 2))
        store.save("new", byteArrayOf(3, 4))
        store.prune(listOf(record("new", 2), record("old", 1)), 1)

        assertTrue(store.hasAudio("new"))
        assertFalse(store.hasAudio("old"))
    }

    @Test
    fun zeroRetentionClearsAllAudio() {
        store.save("one", byteArrayOf(1, 2))
        store.prune(listOf(record("one", 1)), 0)
        assertFalse(store.hasAudio("one"))
    }

    @Test
    fun captureNormalizesSupportedExternalPcmTo16kMono() {
        val prefs = Prefs(context).apply {
            disableAsrHistory = false
            audioHistoryRetentionCount = 10
        }
        val capture = AsrHistoryAudioCapture.create(context, prefs, "normalized")
        capture.onAudioFrame(ByteArray(1_600), sampleRate = 8_000, channels = 1)

        assertTrue(capture.complete())
        repeat(40) {
            if (store.hasAudio("normalized")) return@repeat
            Thread.sleep(25)
        }
        assertTrue((store.readAudio("normalized")?.size ?: 0) >= 3_000)
    }

    @Test
    fun explicitSinkDoesNotWriteAnotherSession() {
        val prefs = Prefs(context).apply {
            disableAsrHistory = false
            audioHistoryRetentionCount = 10
        }
        val first = AsrHistoryAudioCapture.create(context, prefs, "first")
        val second = AsrHistoryAudioCapture.create(context, prefs, "second")
        first.onAudioFrame(ByteArray(640), sampleRate = 16_000, channels = 1)

        assertTrue(first.complete())
        assertFalse(second.complete())
        waitForAudio("first")
        assertTrue(store.hasAudio("first"))
        assertFalse(store.hasAudio("second"))
    }

    @Test
    fun clearInvalidatesQueuedAsyncSave() {
        val prefs = Prefs(context).apply {
            disableAsrHistory = false
            audioHistoryRetentionCount = 10
        }
        AsrHistoryAudioStore.saveAsync(context, "pending", ByteArray(8 * 1024 * 1024))

        store.clearAll()
        Thread.sleep(500)

        assertFalse(store.hasAudio("pending"))
        assertTrue(prefs.audioHistoryRetentionCount > 0)
    }

    private fun waitForAudio(id: String) {
        repeat(40) {
            if (store.hasAudio(id)) return
            Thread.sleep(25)
        }
    }

    private fun record(id: String, timestamp: Long) = AsrHistoryStore.AsrHistoryRecord(
        id = id,
        timestamp = timestamp,
        text = id,
        rawText = id,
        vendorId = "volc",
        audioMs = 1,
        source = "ime",
        aiProcessed = false,
        charCount = id.length
    )
}
