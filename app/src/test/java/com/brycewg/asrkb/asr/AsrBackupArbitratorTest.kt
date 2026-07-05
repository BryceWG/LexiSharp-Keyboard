// Tests the pure ASR backup arbitration decision kernel.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBackupArbitratorTest {
    @Test
    fun primaryFinalWinsEvenWhenBackupFinalArrivesFirst() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryFinal("primary"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("primary", AsrBackupArbitrationSource.Primary)),
            commands
        )
    }

    @Test
    fun immediatePrimaryErrorWaitsForBackupAndThenDeliversBackupFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(
            arbitrator.onEvent(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = "HTTP 503",
                    strategy = AsrPrimaryErrorStrategy.ImmediateFailover
                )
            ).isEmpty()
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun blankPrimaryFinalWinsOverCachedBackupFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryFinal(""))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("", AsrBackupArbitrationSource.Primary)),
            commands
        )
    }

    @Test
    fun switchDeadlineDeliversCachedBackupFinalWhenPrimaryHasNoFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached)

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun latePrimaryFinalWinsAfterSwitchDeadlineWhenBackupIsStillPending() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryFinal("primary"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("primary", AsrBackupArbitrationSource.Primary)),
            commands
        )
    }

    @Test
    fun backupErrorAfterSwitchDeadlineWaitsForPendingPrimaryFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached).isEmpty())
        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupError("backup failed")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryFinal("primary"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("primary", AsrBackupArbitrationSource.Primary)),
            commands
        )
    }

    @Test
    fun backupErrorBeforeSwitchDeadlineStillWaitsForPendingPrimaryAtDeadline() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupError("backup failed")).isEmpty())
        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryFinal("primary"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("primary", AsrBackupArbitrationSource.Primary)),
            commands
        )
    }

    @Test
    fun missingPrimaryDeliversBackupFinalImmediately() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = false,
            hasBackup = true
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun missingBackupDeliversPrimaryError() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = false
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryError("primary failed"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverError("primary failed")),
            commands
        )
    }

    @Test
    fun immediatePrimaryErrorDeliversPrimaryErrorWhenBackupAlsoFails() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(
            arbitrator.onEvent(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = "HTTP 401",
                    strategy = AsrPrimaryErrorStrategy.ImmediateFailover
                )
            ).isEmpty()
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.BackupError("backup failed"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverError("HTTP 401 (backup: backup failed)")),
            commands
        )
    }

    @Test
    fun immediatePrimaryErrorDeliversCachedBackupFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())

        val commands = arbitrator.onEvent(
            AsrBackupArbitrationEvent.PrimaryError(
                message = "HTTP 401",
                strategy = AsrPrimaryErrorStrategy.ImmediateFailover
            )
        )

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun immediatePrimaryErrorWaitsForLaterBackupFinal() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(
            arbitrator.onEvent(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = "HTTP 500",
                    strategy = AsrPrimaryErrorStrategy.ImmediateFailover
                )
            ).isEmpty()
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup"))

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun waitTimeoutPrimaryErrorDoesNotUseCachedBackupUntilSwitchDeadline() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())
        assertTrue(
            arbitrator.onEvent(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = "read timed out",
                    strategy = AsrPrimaryErrorStrategy.WaitTimeout
                )
            ).isEmpty()
        )

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached)

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun waitTimeoutPrimaryErrorDoesNotUseLaterBackupUntilSwitchDeadline() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(
            arbitrator.onEvent(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = "read timed out",
                    strategy = AsrPrimaryErrorStrategy.WaitTimeout
                )
            ).isEmpty()
        )
        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached)

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }

    @Test
    fun unknownPrimaryErrorDefaultsToWaitingForSwitchDeadline() {
        val arbitrator = AsrBackupArbitrator(
            hasPrimary = true,
            hasBackup = true
        )

        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.BackupFinal("backup")).isEmpty())
        assertTrue(arbitrator.onEvent(AsrBackupArbitrationEvent.PrimaryError("primary failed")).isEmpty())

        val commands = arbitrator.onEvent(AsrBackupArbitrationEvent.SwitchDeadlineReached)

        assertEquals(
            listOf(AsrBackupArbitrationCommand.DeliverFinal("backup", AsrBackupArbitrationSource.Backup)),
            commands
        )
    }
}
