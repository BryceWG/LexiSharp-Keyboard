package com.brycewg.asrkb.ui.settings.asr

internal interface AsrSettingsSection {
    fun bind(binding: AsrSettingsBinding)

    fun render(binding: AsrSettingsBinding, state: AsrSettingsUiState) {}

    fun onResume(binding: AsrSettingsBinding) {}

    fun onPause(binding: AsrSettingsBinding) {}
}
