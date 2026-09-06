// Tests the shared terminal coordinator used by ASR primary/backup wrappers.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAsrTerminalCoordinatorTest {
    @Test
    fun deliversOnlyOneTerminalResult() {
        val recorder = Recorder()
        val coordinator = recorder.createCoordinator(hasPrimary = true, hasBackup = false)

        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryFinal("first"))
        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryFinal("late final"))
        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryError("late error"))

        assertEquals(listOf("first" to AsrBackupArbitrationSource.Primary), recorder.finals)
        assertTrue(recorder.errors.isEmpty())
        assertTrue(coordinator.terminalDelivered)
        assertFalse(coordinator.wasLastResultFromBackup())
    }

    @Test
    fun recordsBackupFinalSource() {
        val recorder = Recorder()
        val coordinator = recorder.createCoordinator(hasPrimary = true, hasBackup = true)

        coordinator.dispatch(
            AsrBackupArbitrationEvent.PrimaryError(
                message = "HTTP 503",
                strategy = AsrPrimaryErrorStrategy.ImmediateFailover
            )
        )
        coordinator.dispatch(AsrBackupArbitrationEvent.BackupFinal("backup"))

        assertEquals(listOf("backup" to AsrBackupArbitrationSource.Backup), recorder.finals)
        assertTrue(coordinator.wasLastResultFromBackup())
    }

    @Test
    fun ignoresLateEventsAfterErrorDelivery() {
        val recorder = Recorder()
        val coordinator = recorder.createCoordinator(hasPrimary = true, hasBackup = false)

        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryError("primary failed"))
        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryFinal("late primary"))
        coordinator.dispatch(AsrBackupArbitrationEvent.BackupFinal("late backup"))

        assertEquals(listOf("primary failed"), recorder.errors)
        assertTrue(recorder.finals.isEmpty())
        assertTrue(coordinator.terminalDelivered)
        assertFalse(coordinator.wasLastResultFromBackup())
    }

    @Test
    fun primaryFinalCanStillWinAfterSwitchDeadlineWhenBackupIsPending() {
        val recorder = Recorder()
        val coordinator = recorder.createCoordinator(hasPrimary = true, hasBackup = true)

        coordinator.dispatch(AsrBackupArbitrationEvent.SwitchDeadlineReached)
        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryFinal("primary"))
        coordinator.dispatch(AsrBackupArbitrationEvent.BackupFinal("late backup"))

        assertEquals(listOf("primary" to AsrBackupArbitrationSource.Primary), recorder.finals)
        assertTrue(recorder.errors.isEmpty())
        assertFalse(coordinator.wasLastResultFromBackup())
    }

    @Test
    fun blankPrimaryFinalDeliversOnceAndIgnoresLateBackupFinal() {
        val recorder = Recorder()
        val coordinator = recorder.createCoordinator(hasPrimary = true, hasBackup = true)

        coordinator.dispatch(AsrBackupArbitrationEvent.PrimaryFinal(""))
        coordinator.dispatch(AsrBackupArbitrationEvent.BackupFinal("late backup"))

        assertEquals(listOf("" to AsrBackupArbitrationSource.Primary), recorder.finals)
        assertTrue(recorder.errors.isEmpty())
        assertTrue(coordinator.terminalDelivered)
        assertFalse(coordinator.wasLastResultFromBackup())
    }

    private class Recorder {
        val finals = mutableListOf<Pair<String, AsrBackupArbitrationSource>>()
        val errors = mutableListOf<String>()

        fun createCoordinator(
            hasPrimary: Boolean,
            hasBackup: Boolean
        ): BackupAsrTerminalCoordinator = BackupAsrTerminalCoordinator(
            onFinal = { text, source -> finals += text to source },
            onError = { message -> errors += message }
        ).also { coordinator ->
            coordinator.reset(
                hasPrimary = hasPrimary,
                hasBackup = hasBackup
            )
        }
    }
}
