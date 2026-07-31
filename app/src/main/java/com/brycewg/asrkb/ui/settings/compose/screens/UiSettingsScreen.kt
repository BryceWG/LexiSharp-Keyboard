/**
 * Compose 界面设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics

@Composable
fun UiSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenKeyboardLayout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var uiState by remember(context) { mutableStateOf(InputSettingsUiState.fromPrefs(context, prefs)) }
    var lastHapticLevel by remember { mutableStateOf(prefs.hapticFeedbackLevel) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }

    fun refreshState() {
        uiState = InputSettingsUiState.fromPrefs(context, prefs)
        lastHapticLevel = uiState.hapticFeedbackLevel
    }

    fun applyExplainedSwitch(
        current: Boolean,
        target: Boolean,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        preCheck: ((Boolean) -> Boolean)?,
        onChanged: ((Boolean) -> Unit)?,
        write: (Boolean) -> Unit
    ) {
        featureExplainerDialog = inputExplainedSwitchDialogState(
            context = context,
            current = current,
            target = target,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            preferenceKey = preferenceKey,
            preCheck = preCheck,
            onChanged = onChanged,
            write = write,
            onRefreshState = ::refreshState
        )
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.section_ui_settings,
        onBack = onBack
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("ui") {
                InputUiSettingsSection(
                    uiMode = uiMode,
                    prefs = prefs,
                    uiState = uiState,
                    lastHapticLevel = lastHapticLevel,
                    onUiStateChange = { uiState = it },
                    onLastHapticLevelChange = { lastHapticLevel = it },
                    onRefreshState = ::refreshState,
                    onShowExtensionButtonsPicker = onOpenKeyboardLayout,
                    onApplyExplainedSwitch = ::applyExplainedSwitch
                )
            }
        }
        SettingsFeatureExplainerDialog(
            state = featureExplainerDialog,
            uiMode = uiMode,
            onDismiss = { featureExplainerDialog = null }
        )
    }
}
