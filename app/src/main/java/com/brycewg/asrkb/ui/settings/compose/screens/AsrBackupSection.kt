/**
 * Compose ASR 设置页的备用识别引擎区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.BackupAsrLocalResidency
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun AsrBackupSection(
    uiMode: BibiUiMode,
    enabled: Boolean,
    vendorName: String,
    sensitivity: Int,
    localResidency: BackupAsrLocalResidency,
    onEnabledChange: (Boolean) -> Unit,
    onVendorClick: () -> Unit,
    onSensitivityClick: () -> Unit,
    onLocalResidencyClick: () -> Unit
) {
    AsrSection(uiMode = uiMode, titleRes = R.string.label_backup_asr_engine) {
        val itemCount = if (enabled) 4 else 1
        AsrSwitchPreference(
            id = "backup_asr_enabled",
            titleRes = R.string.label_backup_asr_enabled,
            checked = enabled,
            index = 0,
            count = itemCount,
            onCheckedChange = onEnabledChange
        )
        if (enabled) {
            AsrValuePreference(
                titleRes = R.string.label_backup_asr_vendor,
                value = vendorName,
                uiMode = uiMode,
                index = 1,
                count = itemCount,
                onClick = onVendorClick
            )
            AsrValuePreference(
                titleRes = R.string.label_backup_asr_timeout_sensitivity,
                value = backupTimeoutSensitivityLabel(sensitivity),
                uiMode = uiMode,
                index = 2,
                count = itemCount,
                onClick = onSensitivityClick
            )
            AsrValuePreference(
                titleRes = R.string.label_backup_asr_local_residency,
                value = backupLocalResidencyLabel(localResidency),
                uiMode = uiMode,
                index = 3,
                count = itemCount,
                onClick = onLocalResidencyClick
            )
            AsrBodyText(
                uiMode = uiMode,
                textRes = R.string.hint_backup_asr_uses_existing_config
            )
            AsrBodyText(
                uiMode = uiMode,
                textRes = R.string.hint_backup_asr_timeout_sensitivity
            )
            AsrBodyText(
                uiMode = uiMode,
                textRes = R.string.hint_backup_asr_local_residency
            )
        }
    }
}

@Composable
private fun backupTimeoutSensitivityLabel(value: Int): String = when (value.coerceIn(0, 2)) {
    0 -> stringResource(R.string.option_backup_asr_timeout_sensitivity_relaxed)
    2 -> stringResource(R.string.option_backup_asr_timeout_sensitivity_sensitive)
    else -> stringResource(R.string.option_backup_asr_timeout_sensitivity_balanced)
}

@Composable
private fun backupLocalResidencyLabel(value: BackupAsrLocalResidency): String = when (value) {
    BackupAsrLocalResidency.OnDemand -> stringResource(R.string.option_backup_asr_local_residency_on_demand)
    BackupAsrLocalResidency.Resident -> stringResource(R.string.option_backup_asr_local_residency_resident)
}
