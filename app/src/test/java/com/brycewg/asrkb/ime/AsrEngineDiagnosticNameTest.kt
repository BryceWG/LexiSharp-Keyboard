package com.brycewg.asrkb.ime

import com.brycewg.asrkb.asr.AsrDirectMicrophoneEngineFactory
import com.brycewg.asrkb.asr.AsrEngineModePreferences
import com.brycewg.asrkb.asr.AsrParallelEngineDecision
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.StreamingAsrEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrEngineDiagnosticNameTest {
    @Test
    fun namesAreStableAcrossDirectAndBackupEngines() {
        val directIdentity = AsrDirectMicrophoneEngineFactory().resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(volcStreamingEnabled = true)
        ).identity

        assertEquals("null", asrEngineDiagnosticName(null, null))
        assertEquals("DirectAsrEngine", asrEngineDiagnosticName(FakeEngine(), null))
        assertEquals("VolcStreamAsrEngine", asrEngineDiagnosticName(FakeEngine(), directIdentity))
        assertEquals(
            "ParallelAsrEngine",
            asrEngineDiagnosticName(FakeBackupEngine(AsrParallelEngineDecision.UseParallel), null)
        )
        assertEquals(
            "LazyLocalBackupAsrEngine",
            asrEngineDiagnosticName(FakeBackupEngine(AsrParallelEngineDecision.UseLazyLocalBackup), null)
        )
    }

    private open class FakeEngine : StreamingAsrEngine {
        override val isRunning: Boolean = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    private class FakeBackupEngine(
        override val backupStrategy: AsrParallelEngineDecision
    ) : FakeEngine(), BackupAwareAsrEngine {
        override val primaryVendor: AsrVendor = AsrVendor.Volc
        override val backupVendor: AsrVendor = AsrVendor.OpenAI

        override fun wasLastResultFromBackup(): Boolean = false
    }
}
