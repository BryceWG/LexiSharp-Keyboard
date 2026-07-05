// Backup ASR enablement policy shared by ASR calling channels.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs

internal data class AsrBackupPolicyInput(
    val backupEnabled: Boolean,
    val primaryVendor: AsrVendor,
    val backupVendor: AsrVendor,
    val localBackupResidency: BackupAsrLocalResidency = BackupAsrLocalResidency.OnDemand,
    val availabilityChecks: AsrBackupAvailabilityChecks
)

internal data class AsrBackupAvailabilityChecks(
    val checkBackupVendorAvailability: (AsrVendor) -> AsrVendorReadiness
)

internal enum class AsrBackupPolicyDecision {
    UsePrimaryOnly,
    UseParallel,
    UseLazyLocalBackup
}

enum class BackupAsrLocalResidency(val id: String) {
    OnDemand("on_demand"),
    Resident("resident");

    companion object {
        fun fromId(id: String?): BackupAsrLocalResidency =
            entries.firstOrNull { it.id == id } ?: OnDemand
    }
}

internal fun shouldUseBackupAsr(
    context: Context,
    prefs: Prefs,
    primaryVendor: AsrVendor,
    backupVendor: AsrVendor
): Boolean {
    val enabled = try {
        prefs.backupAsrEnabled
    } catch (_: Throwable) {
        false
    }
    return shouldUseBackupAsr(
        AsrBackupPolicyInput(
            backupEnabled = enabled,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            localBackupResidency = prefs.backupAsrLocalResidency,
            availabilityChecks = AsrBackupAvailabilityChecks { vendor ->
                checkAsrVendorAvailability(context, prefs, vendor)
            }
        )
    )
}

internal fun resolveBackupAsrDecision(
    context: Context,
    prefs: Prefs,
    primaryVendor: AsrVendor,
    backupVendor: AsrVendor
): AsrBackupPolicyDecision {
    val enabled = try {
        prefs.backupAsrEnabled
    } catch (_: Throwable) {
        false
    }
    return resolveBackupAsrDecision(
        AsrBackupPolicyInput(
            backupEnabled = enabled,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            localBackupResidency = prefs.backupAsrLocalResidency,
            availabilityChecks = AsrBackupAvailabilityChecks { vendor ->
                checkAsrVendorAvailability(context, prefs, vendor)
            }
        )
    )
}

internal fun shouldUseBackupAsr(input: AsrBackupPolicyInput): Boolean {
    return resolveBackupAsrDecision(input) != AsrBackupPolicyDecision.UsePrimaryOnly
}

internal fun resolveBackupAsrDecision(input: AsrBackupPolicyInput): AsrBackupPolicyDecision {
    if (!input.backupEnabled) return AsrBackupPolicyDecision.UsePrimaryOnly
    if (input.backupVendor == input.primaryVendor) return AsrBackupPolicyDecision.UsePrimaryOnly
    val readiness = try {
        input.availabilityChecks
            .checkBackupVendorAvailability(input.backupVendor)
    } catch (_: Throwable) {
        return AsrBackupPolicyDecision.UsePrimaryOnly
    }
    if (!readiness.isUsable) return AsrBackupPolicyDecision.UsePrimaryOnly

    return when (readiness.classification) {
        AsrVendorAvailabilityClassification.LocalModelReadiness ->
            if (input.localBackupResidency == BackupAsrLocalResidency.Resident) {
                AsrBackupPolicyDecision.UseParallel
            } else {
                AsrBackupPolicyDecision.UseLazyLocalBackup
            }
        AsrVendorAvailabilityClassification.OnlineConfiguration ->
            AsrBackupPolicyDecision.UseParallel
    }
}
