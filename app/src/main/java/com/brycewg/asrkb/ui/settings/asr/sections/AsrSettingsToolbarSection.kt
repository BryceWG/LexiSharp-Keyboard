package com.brycewg.asrkb.ui.settings.asr.sections

import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsBinding
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsSection
import com.google.android.material.appbar.MaterialToolbar

internal class AsrSettingsToolbarSection : AsrSettingsSection {
    override fun bind(binding: AsrSettingsBinding) {
        val toolbar = binding.view<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_asr_settings)
        toolbar.setNavigationOnClickListener { binding.activity.finish() }
    }
}
