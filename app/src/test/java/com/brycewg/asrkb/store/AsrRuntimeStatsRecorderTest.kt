// Tests the primary-success recording rule shared by ASR invocation channels.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.AsrParallelEngineDecision
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.StreamingAsrEngine
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrRuntimeStatsRecorderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun directPrimarySuccessRecordsRuntimeSample() {
        val prefs = Prefs(context)

        prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
            engine = FakeEngine(),
            fallbackPrimaryVendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L
        )

        assertEquals(
            1,
            prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 1_000L).requestSampleCount
        )
    }

    @Test
    fun backupFinalDoesNotRecordPrimaryRuntimeSample() {
        val prefs = Prefs(context)

        prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
            engine = FakeBackupAwareEngine(fromBackup = true),
            fallbackPrimaryVendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L
        )

        assertEquals(
            0,
            prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 1_000L).requestSampleCount
        )
    }

    @Test
    fun primaryFinalFromBackupAwareEngineRecordsPrimaryVendor() {
        val prefs = Prefs(context)

        prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
            engine = FakeBackupAwareEngine(fromBackup = false),
            fallbackPrimaryVendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L
        )

        assertEquals(
            1,
            prefs.getAsrRuntimeStatsSnapshot(AsrVendor.Volc, 1_000L).requestSampleCount
        )
        assertEquals(
            0,
            prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 1_000L).requestSampleCount
        )
    }

    @Test
    fun missingRequestDurationDoesNotRecordRuntimeSample() {
        val prefs = Prefs(context)

        prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
            engine = FakeEngine(),
            fallbackPrimaryVendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = null
        )

        assertEquals(
            0,
            prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 1_000L).requestSampleCount
        )
    }

    private open class FakeEngine : StreamingAsrEngine {
        override val isRunning: Boolean = false
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class FakeBackupAwareEngine(
        private val fromBackup: Boolean
    ) : FakeEngine(),
        BackupAwareAsrEngine {
        override val primaryVendor: AsrVendor = AsrVendor.Volc
        override val backupVendor: AsrVendor = AsrVendor.SenseVoice
        override val backupStrategy: AsrParallelEngineDecision =
            AsrParallelEngineDecision.UseLazyLocalBackup

        override fun wasLastResultFromBackup(): Boolean = fromBackup
    }
}
