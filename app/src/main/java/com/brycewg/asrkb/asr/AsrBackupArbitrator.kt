// Pure decision kernel for ASR primary/backup result arbitration.
package com.brycewg.asrkb.asr

internal enum class AsrBackupArbitrationSource {
    Primary,
    Backup
}

internal sealed class AsrBackupArbitrationEvent {
    data class PrimaryFinal(val text: String) : AsrBackupArbitrationEvent()
    data class PrimaryError(
        val message: String,
        val strategy: AsrPrimaryErrorStrategy = AsrPrimaryErrorClassifier.classifyMessage(message)
    ) : AsrBackupArbitrationEvent()
    data class BackupFinal(val text: String) : AsrBackupArbitrationEvent()
    data class BackupError(val message: String) : AsrBackupArbitrationEvent()
    data object SwitchDeadlineReached : AsrBackupArbitrationEvent()
}

internal sealed class AsrBackupArbitrationCommand {
    data class DeliverFinal(
        val text: String,
        val source: AsrBackupArbitrationSource
    ) : AsrBackupArbitrationCommand()

    data class DeliverError(val message: String) : AsrBackupArbitrationCommand()
}

internal class AsrBackupArbitrator(
    private val hasPrimary: Boolean,
    private val hasBackup: Boolean
) {
    private var delivered = false
    private var primaryTerminal: Terminal? = null
    private var backupTerminal: Terminal? = null
    private var switchDeadlineReached = false

    fun onEvent(event: AsrBackupArbitrationEvent): List<AsrBackupArbitrationCommand> {
        if (delivered) return emptyList()
        when (event) {
            is AsrBackupArbitrationEvent.PrimaryFinal -> primaryTerminal = Terminal.Final(event.text)
            is AsrBackupArbitrationEvent.PrimaryError -> primaryTerminal = Terminal.Error(event.message, event.strategy)
            is AsrBackupArbitrationEvent.BackupFinal -> backupTerminal = Terminal.Final(event.text)
            is AsrBackupArbitrationEvent.BackupError -> backupTerminal = Terminal.Error(event.message)
            AsrBackupArbitrationEvent.SwitchDeadlineReached -> switchDeadlineReached = true
        }
        return resolve()
    }

    private fun resolve(): List<AsrBackupArbitrationCommand> {
        val primary = primaryTerminal
        val backup = backupTerminal

        if (!hasPrimary) {
            return when (backup) {
                is Terminal.Final -> deliverFinal(backup.text, AsrBackupArbitrationSource.Backup)
                is Terminal.Error -> deliverError(backup.message)
                null -> emptyList()
            }
        }

        if (primary is Terminal.Final) {
            return deliverFinal(primary.text, AsrBackupArbitrationSource.Primary)
        }

        if (!hasBackup) {
            return when (primary) {
                is Terminal.Final -> deliverFinal(primary.text, AsrBackupArbitrationSource.Primary)
                is Terminal.Error -> deliverError(primary.message)
                null -> emptyList()
            }
        }

        val primaryFailed =
            primary is Terminal.Error &&
                primary.strategy == AsrPrimaryErrorStrategy.ImmediateFailover

        if (primaryFailed) {
            return deliverBackupTerminal(primary, backup, allowBackupErrorWithoutPrimary = true)
        }

        if (switchDeadlineReached) {
            return deliverBackupTerminal(primary, backup, allowBackupErrorWithoutPrimary = false)
        }

        return emptyList()
    }

    private fun deliverBackupTerminal(
        primary: Terminal?,
        backup: Terminal?,
        allowBackupErrorWithoutPrimary: Boolean
    ): List<AsrBackupArbitrationCommand> =
        when (backup) {
            is Terminal.Final -> deliverFinal(backup.text, AsrBackupArbitrationSource.Backup)
            is Terminal.Error ->
                if (primary != null || allowBackupErrorWithoutPrimary) {
                    deliverError(preferPrimaryError(primary, backup.message))
                } else {
                    emptyList()
                }
            null -> emptyList()
        }

    private fun preferPrimaryError(primary: Terminal?, backupMessage: String): String {
        val primaryMessage = (primary as? Terminal.Error)?.message
            ?.takeIf { it.isNotBlank() }
        return if (primaryMessage == null) {
            backupMessage
        } else {
            "$primaryMessage (backup: $backupMessage)"
        }
    }

    private fun deliverFinal(
        text: String,
        source: AsrBackupArbitrationSource
    ): List<AsrBackupArbitrationCommand> {
        delivered = true
        return listOf(AsrBackupArbitrationCommand.DeliverFinal(text, source))
    }

    private fun deliverError(message: String): List<AsrBackupArbitrationCommand> {
        delivered = true
        return listOf(AsrBackupArbitrationCommand.DeliverError(message))
    }

    private sealed class Terminal {
        data class Final(val text: String) : Terminal()
        data class Error(
            val message: String,
            val strategy: AsrPrimaryErrorStrategy = AsrPrimaryErrorStrategy.ImmediateFailover
        ) : Terminal()
    }
}
