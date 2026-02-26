package com.brycewg.asrkb.ui.settings.asr.sections

import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.partitionAsrVendorsByConfigured
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection

internal class AsrVendorSelectionSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        val tvAsrVendor = binding.view<TextView>(R.id.tvAsrVendorValue)
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

        tvAsrVendor.setOnClickListener { v ->
            binding.hapticTapIfEnabled(v)
            val curIdx = vendorOrder.indexOf(binding.prefs.asrVendor).coerceAtLeast(0)
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
                titleResId = R.string.label_asr_vendor,
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
                val vendor = vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.Volc
                binding.viewModel.updateVendor(vendor)
            }
        }
    }
}
