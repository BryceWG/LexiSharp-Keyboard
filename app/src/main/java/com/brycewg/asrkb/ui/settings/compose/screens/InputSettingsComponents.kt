/**
 * Compose 输入设置页的纯 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsHighlightContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSliderPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsValuePreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
internal fun InputScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_input_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun InputSection(
    uiMode: BibiUiMode,
    titleRes: Int,
    content: @Composable () -> Unit
) {
    SettingsSectionContainer(uiMode = uiMode, titleRes = titleRes) {
        content()
    }
}

@Composable
internal fun InputExplainedSwitch(
    id: String,
    titleRes: Int,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    index: Int = 0,
    count: Int = 1
) {
    SettingsPreference(
        entry = SettingsEntry.Switch(
            id = id,
            titleRes = titleRes,
            checked = checked,
            onCheckedChange = onToggle
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun InputValuePreference(
    titleRes: Int,
    value: String,
    uiMode: BibiUiMode,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        SettingsValuePreference(
            titleRes = titleRes,
            value = value,
            uiMode = uiMode,
            index = index,
            count = count,
            onClick = onClick
        )
    }
    if (highlightId == null) content() else SettingsHighlightContainer(highlightId, uiMode, content)
}

@Composable
internal fun InputKeyboardHeightControl(
    selectedTier: Int,
    uiMode: BibiUiMode,
    index: Int = 0,
    count: Int = 1,
    onSelected: (Int) -> Unit
) {
    val labels = listOf(
        1 to stringResource(R.string.keyboard_height_small),
        2 to stringResource(R.string.keyboard_height_medium),
        3 to stringResource(R.string.keyboard_height_large)
    )
    SettingsPreference(
        SettingsEntry.Dropdown(
            id = "keyboard_height",
            titleRes = R.string.label_keyboard_height,
            options = labels.map { (tier, label) -> DropdownOption(tier.toString(), label) },
            selectedOptionId = selectedTier.toString(),
            onSelectedOptionChange = { id -> onSelected(id.toIntOrNull()?.coerceIn(1, 3) ?: 2) }
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun InputSliderPreference(
    titleRes: Int,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    uiMode: BibiUiMode,
    showKeyPoints: Boolean = steps in 1..10,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit = { _ -> }
) {
    SettingsSliderPreference(
        uiMode = uiMode,
        title = stringResource(titleRes),
        valueLabel = valueLabel,
        value = value,
        valueRange = valueRange,
        steps = steps,
        showKeyPoints = showKeyPoints,
        highlightId = highlightId,
        index = index,
        count = count,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished
    )
}
