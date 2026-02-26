package com.brycewg.asrkb.ui.settings.asr.sections

import android.view.View
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.partitionAsrVendorsByConfigured
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.google.android.material.materialswitch.MaterialSwitch

internal class BackupAsrSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        val switchBackupAsrEnabled = binding.view<MaterialSwitch>(R.id.switchBackupAsrEnabled)
        val groupBackupAsr = binding.view<View>(R.id.groupBackupAsr)
        val tvBackupAsrVendor = binding.view<TextView>(R.id.tvBackupAsrVendorValue)
        val tvBackupAsrTimeoutSensitivity = binding.view<TextView>(R.id.tvBackupAsrTimeoutSensitivityValue)

        fun updateVendorSummary() {
            val vendorOrder = AsrVendorUi.ordered()
            val vendorItems = AsrVendorUi.names(binding.activity)
            val idx = vendorOrder.indexOf(binding.prefs.backupAsrVendor).coerceAtLeast(0)
            tvBackupAsrVendor.text = vendorItems[idx]
        }

        fun updateTimeoutSensitivitySummary() {
            val resId = when (binding.prefs.backupAsrTimeoutSensitivity) {
                0 -> R.string.option_backup_asr_timeout_sensitivity_relaxed
                2 -> R.string.option_backup_asr_timeout_sensitivity_sensitive
                else -> R.string.option_backup_asr_timeout_sensitivity_balanced
            }
            tvBackupAsrTimeoutSensitivity.text = binding.activity.getString(resId)
        }

        fun updateBackupUi() {
            val enabled = binding.prefs.backupAsrEnabled
            groupBackupAsr.visibility = if (enabled) View.VISIBLE else View.GONE
            updateVendorSummary()
            updateTimeoutSensitivitySummary()
        }

        switchBackupAsrEnabled.isChecked = binding.prefs.backupAsrEnabled
        updateBackupUi()

        switchBackupAsrEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.prefs.backupAsrEnabled = isChecked
            updateBackupUi()
            binding.hapticTapIfEnabled(switchBackupAsrEnabled)
        }

        tvBackupAsrVendor.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val vendorOrder = AsrVendorUi.ordered()
            val vendorItems = vendorOrder.map { vendor ->
                SettingsOptionSheet.TaggedItem(
                    title = AsrVendorUi.name(binding.activity, vendor),
                    tags = AsrVendorUi.tags(vendor).map { tag ->
                        SettingsOptionSheet.Tag(
                            label = binding.activity.getString(tag.labelResId),
                            bgColorResId = tag.bgColorResId,
                            textColorResId = tag.textColorResId,
                        )
                    },
                )
            }
            val curIdx = vendorOrder.indexOf(binding.prefs.backupAsrVendor).coerceAtLeast(0)
            val indexByVendor = vendorOrder.withIndex().associate { it.value to it.index }
            val partition = partitionAsrVendorsByConfigured(
                context = binding.activity,
                prefs = binding.prefs,
                vendors = vendorOrder,
            )
            val configuredItems = partition.configured.mapNotNull { vendor ->
                indexByVendor[vendor]?.let { idx ->
                    SettingsOptionSheet.TaggedIndexedItem(
                        originalIndex = idx,
                        item = vendorItems[idx],
                    )
                }
            }
            val unconfiguredItems = partition.unconfigured.mapNotNull { vendor ->
                indexByVendor[vendor]?.let { idx ->
                    SettingsOptionSheet.TaggedIndexedItem(
                        originalIndex = idx,
                        item = vendorItems[idx],
                    )
                }
            }
            SettingsOptionSheet.showSingleChoiceTaggedGrouped(
                context = binding.activity,
                titleResId = R.string.label_backup_asr_vendor,
                groups = listOf(
                    SettingsOptionSheet.TaggedGroup(
                        label = binding.activity.getString(R.string.asr_vendor_group_configured),
                        items = configuredItems,
                    ),
                    SettingsOptionSheet.TaggedGroup(
                        label = binding.activity.getString(R.string.asr_vendor_group_unconfigured),
                        items = unconfiguredItems,
                    ),
                ),
                selectedIndex = curIdx,
            ) { selectedIdx ->
                val vendor = vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.SiliconFlow
                binding.prefs.backupAsrVendor = vendor
                updateVendorSummary()
            }
        }

        tvBackupAsrTimeoutSensitivity.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val items = arrayOf(
                binding.activity.getString(R.string.option_backup_asr_timeout_sensitivity_relaxed),
                binding.activity.getString(R.string.option_backup_asr_timeout_sensitivity_balanced),
                binding.activity.getString(R.string.option_backup_asr_timeout_sensitivity_sensitive),
            )
            val curIdx = binding.prefs.backupAsrTimeoutSensitivity.coerceIn(0, 2)
            binding.showSingleChoiceDialog(
                titleResId = R.string.label_backup_asr_timeout_sensitivity,
                items = items,
                currentIndex = curIdx,
            ) { selectedIdx ->
                binding.prefs.backupAsrTimeoutSensitivity = selectedIdx.coerceIn(0, 2)
                updateTimeoutSensitivitySummary()
            }
        }
    }
}
