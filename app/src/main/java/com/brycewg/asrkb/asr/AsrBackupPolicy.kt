// Backup ASR enablement policy shared by ASR calling channels.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs

internal data class AsrBackupPolicyInput(
    val backupEnabled: Boolean,
    val primaryVendor: AsrVendor,
    val backupVendor: AsrVendor,
    val availabilityChecks: AsrBackupAvailabilityChecks
)

internal data class AsrBackupAvailabilityChecks(
    val checkBackupVendorAvailability: (AsrVendor) -> AsrVendorReadiness
)

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
            availabilityChecks = AsrBackupAvailabilityChecks { vendor ->
                checkAsrVendorAvailability(context, prefs, vendor)
            }
        )
    )
}

internal fun shouldUseBackupAsr(input: AsrBackupPolicyInput): Boolean {
    if (!input.backupEnabled) return false
    if (input.backupVendor == input.primaryVendor) return false
    return try {
        input.availabilityChecks
            .checkBackupVendorAvailability(input.backupVendor)
            .isUsable
    } catch (_: Throwable) {
        false
    }
}
