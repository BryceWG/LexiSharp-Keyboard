// JVM regression tests for diagnostics log export and next-session cleanup behavior.
package com.brycewg.asrkb.store.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugLogManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DebugLogManager.resetForTest(context)
        DebugLogManager.setShareUriResolverForTest { _, file -> Uri.fromFile(file) }
    }

    @Test
    fun exportSuccessMarksNextVerboseStartCleanupWithoutDeletingCurrentLog() {
        DebugLogManager.logBase(context, "test", "old_log")
        assertTrue(DebugLogManager.flushForTest(context))
        assertTrue(activeLogFile().readText().contains("\"evt\":\"old_log\""))

        val result = DebugLogManager.buildShareIntent(context)

        assertTrue("Expected share success, got $result", result is DebugLogManager.ShareIntentResult.Success)
        assertTrue(activeLogFile().exists())
        assertTrue(activeLogFile().readText().contains("\"evt\":\"old_log\""))
    }

    @Test
    fun exportFlushesQueuedLogLinesBeforeCreatingSnapshot() {
        DebugLogManager.logBase(context, "test", "queued_log")

        val result = DebugLogManager.buildShareIntent(context)

        assertTrue("Expected share success, got $result", result is DebugLogManager.ShareIntentResult.Success)
        val snapshot = snapshotFile(result as DebugLogManager.ShareIntentResult.Success).readText()
        assertTrue(snapshot.contains("\"evt\":\"queued_log\""))
    }

    @Test
    fun nextVerboseStartAfterExportClearsPreviousLogBeforeWritingNewSession() {
        DebugLogManager.logBase(context, "test", "old_log")
        assertTrue(DebugLogManager.flushForTest(context))
        val exportResult = DebugLogManager.buildShareIntent(context)
        assertTrue("Expected share success, got $exportResult", exportResult is DebugLogManager.ShareIntentResult.Success)

        DebugLogManager.start(context)
        assertTrue(DebugLogManager.flushForTest(context))

        val text = activeLogFile().readText()
        assertFalse(text.contains("\"evt\":\"old_log\""))
        assertTrue(text.contains("\"evt\":\"verbose_enabled\""))
        assertTrue(text.contains("\"evt\":\"verbose_session_started\""))
    }

    @Test
    fun noLogExportDoesNotMarkNextVerboseStartCleanup() {
        val result = DebugLogManager.buildShareIntent(context)
        assertTrue(result is DebugLogManager.ShareIntentResult.Error)
        assertEquals(DebugLogManager.ShareError.NoLog, (result as DebugLogManager.ShareIntentResult.Error).error)

        DebugLogManager.logBase(context, "test", "old_log")
        assertTrue(DebugLogManager.flushForTest(context))
        DebugLogManager.start(context)
        assertTrue(DebugLogManager.flushForTest(context))

        val text = activeLogFile().readText()
        assertTrue(text.contains("\"evt\":\"old_log\""))
        assertTrue(text.contains("\"evt\":\"verbose_enabled\""))
    }

    private fun activeLogFile(): File = File(File(context.noBackupFilesDir, "debug"), "diagnostics.jsonl")

    @Suppress("DEPRECATION")
    private fun snapshotFile(result: DebugLogManager.ShareIntentResult.Success): File {
        val uri = result.intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return File(requireNotNull(uri).path!!)
    }
}
