/**
 * Compose 其他设置页的局部 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.other.OtherSettingsViewModel
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OtherScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_other_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun OtherSection(
    uiMode: BibiUiMode,
    titleRes: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionContainer(
        uiMode = uiMode,
        titleRes = titleRes,
        content = content
    )
}

@Composable
internal fun OtherExplainedSwitch(
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
internal fun OtherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    index: Int = 0,
    count: Int = 1,
    materialContainer: Boolean = true,
    applyEdgePadding: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding,
        vertical = SettingsLayoutMetrics.TextFieldLooseVerticalPadding
    )
) {
    SettingsTextField(
        uiMode = uiMode,
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        placeholder = placeholder,
        password = password,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardType = keyboardType,
        visualTransformation = if (password) null else visualTransformation,
        index = index,
        count = count,
        materialContainer = materialContainer,
        applyEdgePadding = applyEdgePadding,
        contentPadding = contentPadding
    )
}

@Composable
internal fun OtherClickableValue(
    text: String,
    uiMode: BibiUiMode,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OtherButton(
        text = text,
        uiMode = uiMode,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
internal fun OtherValuePreference(
    titleRes: Int,
    value: String,
    enabled: Boolean,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Action(
            id = "other_value_$titleRes",
            titleRes = titleRes,
            summary = value,
            enabled = enabled,
            onClick = onClick
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun OtherButtonRow(
    uiMode: BibiUiMode,
    content: @Composable RowScope.() -> Unit
) {
    SettingsActionButtonRow(uiMode = uiMode, content = content)
}

@Composable
internal fun OtherButton(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    padded: Boolean = true
) {
    val buttonModifier = if (padded) {
        modifier
            .padding(horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding)
            .padding(
                top = SettingsLayoutMetrics.ActionButtonRowTopPadding,
                bottom = SettingsLayoutMetrics.ActionButtonRowBottomPadding
            )
    } else {
        modifier
    }
    SettingsActionButton(
        uiMode = uiMode,
        text = text,
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled,
        leadingIcon = leadingIcon
    )
}

@Composable
internal fun OtherBodyText(
    text: String,
    uiMode: BibiUiMode,
    strong: Boolean = false
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (strong) FontWeight.SemiBold else null
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
internal fun SpeechPresetSection(
    uiMode: BibiUiMode,
    state: OtherSettingsViewModel.SpeechPresetsState,
    focusNameAfterAdd: Boolean,
    onFocusNameHandled: () -> Unit,
    onSelectorTap: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateContent: (String) -> Unit,
    onAddPreset: () -> Unit,
    onDeletePreset: () -> Unit
) {
    val context = LocalContext.current
    val nameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.activePresetId, focusNameAfterAdd) {
        if (focusNameAfterAdd && state.isEnabled) {
            nameFocusRequester.requestFocus()
            onFocusNameHandled()
        }
    }
    OtherSection(uiMode = uiMode, titleRes = R.string.label_speech_preset_section) {
        var itemIndex = 0
        val itemCount = 3
        OtherValuePreference(
            titleRes = R.string.label_speech_preset_select,
            value = selectedSpeechPresetLabel(context, state),
            enabled = state.presets.isNotEmpty(),
            index = itemIndex++,
            count = itemCount,
            onClick = {
                if (state.presets.isEmpty()) return@OtherValuePreference
                onSelectorTap()
            }
        )
        OtherTextField(
            value = state.currentPreset?.name.orEmpty(),
            onValueChange = onUpdateName,
            label = stringResource(R.string.hint_speech_preset_name),
            uiMode = uiMode,
            enabled = state.isEnabled,
            modifier = Modifier.focusRequester(nameFocusRequester),
            singleLine = true,
            index = itemIndex++,
            count = itemCount
        )
        OtherTextField(
            value = state.currentPreset?.content.orEmpty(),
            onValueChange = onUpdateContent,
            label = stringResource(R.string.hint_speech_preset_content),
            uiMode = uiMode,
            enabled = state.isEnabled,
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            index = itemIndex,
            count = itemCount
        )
        OtherButtonRow(uiMode = uiMode) {
            OtherButton(
                text = stringResource(R.string.btn_speech_preset_add),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                onClick = onAddPreset,
                leadingIcon = Icons.Rounded.Add,
                padded = false
            )
            OtherButton(
                text = stringResource(R.string.btn_speech_preset_delete),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                enabled = state.isEnabled,
                onClick = onDeletePreset,
                leadingIcon = Icons.Rounded.Delete,
                padded = false
            )
        }
        OtherBodyText(stringResource(R.string.speech_preset_intro), uiMode)
    }
}

@Composable
internal fun SyncClipboardSection(
    uiMode: BibiUiMode,
    state: OtherSettingsViewModel.SyncClipboardState,
    onEnabledChange: (Boolean) -> Unit,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAutoReceiveChange: (Boolean) -> Unit,
    onKeepBackgroundRealtimeChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onTestPull: () -> Unit,
    onOpenProject: () -> Unit
) {
    var intervalText by remember(state.pullIntervalSec) {
        mutableStateOf(state.pullIntervalSec.toString())
    }
    val autoReceiveEnabled = state.receiveMode != ClipboardSyncReceiveMode.OFF
    OtherSection(uiMode = uiMode, titleRes = R.string.section_sync_clipboard) {
        SettingsPreference(
            SettingsEntry.Switch(
                id = "sync_clipboard",
                titleRes = R.string.label_enable_sync_clipboard,
                checked = state.enabled,
                onCheckedChange = onEnabledChange
            )
        )
        if (state.enabled) {
            val fieldPadding = PaddingValues(
                horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding,
                vertical = SettingsLayoutMetrics.TextFieldLooseVerticalPadding
            )
            val credentialFields: @Composable () -> Unit = {
                OtherTextField(
                    value = state.serverBase,
                    onValueChange = onServerChange,
                    label = stringResource(R.string.label_sc_server_base),
                    uiMode = uiMode,
                    keyboardType = KeyboardType.Uri,
                    materialContainer = false,
                    applyEdgePadding = false,
                    contentPadding = fieldPadding
                )
                OtherTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = stringResource(R.string.label_sc_username),
                    uiMode = uiMode,
                    materialContainer = false,
                    applyEdgePadding = false,
                    contentPadding = fieldPadding
                )
                OtherTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.label_sc_password),
                    uiMode = uiMode,
                    keyboardType = KeyboardType.Password,
                    password = true,
                    materialContainer = false,
                    applyEdgePadding = false,
                    contentPadding = fieldPadding
                )
                OtherTextField(
                    value = intervalText,
                    onValueChange = { raw ->
                        intervalText = raw.filter { it.isDigit() }.take(3)
                        intervalText.toIntOrNull()?.let { onIntervalChange(it.coerceIn(1, 600)) }
                    },
                    label = stringResource(R.string.label_sc_pull_interval),
                    uiMode = uiMode,
                    keyboardType = KeyboardType.Number,
                    singleLine = true,
                    materialContainer = false,
                    applyEdgePadding = false,
                    contentPadding = fieldPadding
                )
            }
            when (uiMode) {
                BibiUiMode.Material -> SettingsMaterialItemSurface {
                    credentialFields()
                }

                BibiUiMode.Miuix -> Column {
                    credentialFields()
                }
            }
            SettingsPreference(
                SettingsEntry.Switch(
                    id = "sync_clipboard_auto_receive",
                    titleRes = R.string.label_sc_auto_receive,
                    summary = stringResource(
                        when {
                            state.detectingReceiveMode -> R.string.summary_sc_auto_receive_detecting
                            !autoReceiveEnabled -> R.string.summary_sc_auto_receive_off
                            state.receiveMode == ClipboardSyncReceiveMode.REALTIME ->
                                R.string.summary_sc_auto_receive_realtime
                            else -> R.string.summary_sc_auto_receive_polling
                        }
                    ),
                    checked = autoReceiveEnabled,
                    onCheckedChange = onAutoReceiveChange
                )
            )
            if (state.receiveMode == ClipboardSyncReceiveMode.REALTIME) {
                SettingsPreference(
                    SettingsEntry.Switch(
                        id = "sync_clipboard_keep_background",
                        titleRes = R.string.label_sc_keep_background_realtime,
                        checked = state.keepBackgroundRealtimeEnabled,
                        onCheckedChange = onKeepBackgroundRealtimeChange
                    )
                )
            }
            OtherButtonRow(uiMode = uiMode) {
                OtherButton(
                    text = stringResource(R.string.btn_sc_test_pull),
                    uiMode = uiMode,
                    modifier = Modifier.weight(1f),
                    onClick = onTestPull,
                    padded = false
                )
                OtherButton(
                    text = stringResource(R.string.btn_sc_project_home),
                    uiMode = uiMode,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenProject,
                    padded = false
                )
            }
        }
    }
}

private fun selectedSpeechPresetLabel(
    context: Context,
    state: OtherSettingsViewModel.SpeechPresetsState
): String = if (state.presets.isNotEmpty()) {
    state.currentPreset?.name?.trim()?.ifEmpty {
        context.getString(R.string.speech_preset_untitled)
    } ?: context.getString(R.string.speech_preset_untitled)
} else {
    context.getString(R.string.speech_preset_empty_placeholder)
}
