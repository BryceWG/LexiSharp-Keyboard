/**
 * ASR 设置页备用识别路由区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAsrLocalResidency
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun AsrBackupRouteSection(
    uiMode: BibiUiMode,
    prefs: Prefs,
    enabled: Boolean,
    vendor: AsrVendor,
    vendorName: String,
    sensitivity: Int,
    localResidency: BackupAsrLocalResidency,
    onEnabledChange: (Boolean) -> Unit,
    onVendorChange: (AsrVendor) -> Unit,
    showVendorPicker: (Int, AsrVendor, (AsrVendor) -> Unit) -> Unit,
    showSensitivityPicker: () -> Unit,
    showLocalResidencyPicker: () -> Unit
) {
    AsrBackupSection(
        uiMode = uiMode,
        enabled = enabled,
        vendorName = vendorName,
        sensitivity = sensitivity,
        localResidency = localResidency,
        onEnabledChange = { checked ->
            onEnabledChange(checked)
            prefs.backupAsrEnabled = checked
        },
        onVendorClick = {
            showVendorPicker(R.string.label_backup_asr_vendor, vendor) { selected ->
                onVendorChange(selected)
                prefs.backupAsrVendor = selected
            }
        },
        onSensitivityClick = {
            showSensitivityPicker()
        },
        onLocalResidencyClick = {
            showLocalResidencyPicker()
        }
    )
}
