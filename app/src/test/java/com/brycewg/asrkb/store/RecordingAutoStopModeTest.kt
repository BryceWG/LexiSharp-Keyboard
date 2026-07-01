// 录音自动停止模式迁移规则的 JVM 回归测试。
package com.brycewg.asrkb.store

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAutoStopModeTest {
    @Test
    fun missingModeDefaultsToManualWhenLegacySilenceDisabled() {
        assertEquals(
            Prefs.RecordingAutoStopMode.MANUAL,
            resolveRecordingAutoStopMode(storedModeId = null, legacySilenceEnabled = false)
        )
    }

    @Test
    fun missingModeMigratesLegacySilenceEnabledToSilence() {
        assertEquals(
            Prefs.RecordingAutoStopMode.SILENCE,
            resolveRecordingAutoStopMode(storedModeId = null, legacySilenceEnabled = true)
        )
    }

    @Test
    fun storedModeWinsOverLegacySilenceFlag() {
        assertEquals(
            Prefs.RecordingAutoStopMode.MAX_DURATION,
            resolveRecordingAutoStopMode(
                storedModeId = Prefs.RecordingAutoStopMode.MAX_DURATION.id,
                legacySilenceEnabled = true
            )
        )
    }
}
