/**
 * Compose 设置页功能说明弹窗。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val FEATURE_EXPLAINER_PREFS = "feature_explainer"

internal data class SettingsFeatureExplainerDialogState(
    val title: String,
    val directionText: String,
    val fromLabel: String,
    val toLabel: String,
    val fromDescription: String,
    val toDescription: String,
    val dontShowAgainText: String?,
    val confirmText: String,
    val cancelText: String,
    val onDontShowAgain: () -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit = {}
)

internal fun settingsFeatureExplainerDialogState(
    context: Context,
    @StringRes titleRes: Int,
    @StringRes offDescRes: Int,
    @StringRes onDescRes: Int,
    currentState: Boolean,
    preferenceKey: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit = {}
): SettingsFeatureExplainerDialogState? = settingsFeatureExplainerDialogState(
    context = context,
    title = context.getString(titleRes),
    offDescription = context.getString(offDescRes),
    onDescription = context.getString(onDescRes),
    currentState = currentState,
    preferenceKey = preferenceKey,
    onConfirm = onConfirm,
    onCancel = onCancel
)

internal fun settingsFeatureExplainerDialogState(
    context: Context,
    title: String,
    offDescription: String,
    onDescription: String,
    currentState: Boolean,
    preferenceKey: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit = {}
): SettingsFeatureExplainerDialogState? {
    val normalizedKey = preferenceKey?.takeIf { it.isNotBlank() }
    if (normalizedKey != null && context.hasFeatureExplainerFlag(normalizedKey)) {
        onConfirm()
        return null
    }
    return SettingsFeatureExplainerDialogState(
        title = title,
        directionText = context.getString(
            if (currentState) {
                R.string.dialog_feature_explainer_turn_off
            } else {
                R.string.dialog_feature_explainer_turn_on
            }
        ),
        fromLabel = context.getString(R.string.dialog_feature_explainer_from),
        toLabel = context.getString(R.string.dialog_feature_explainer_to),
        fromDescription = if (currentState) onDescription else offDescription,
        toDescription = if (currentState) offDescription else onDescription,
        dontShowAgainText = normalizedKey?.let {
            context.getString(R.string.dialog_feature_explainer_dont_show_again)
        },
        confirmText = context.getString(R.string.btn_confirm),
        cancelText = context.getString(R.string.btn_cancel),
        onDontShowAgain = {
            normalizedKey?.let { context.saveFeatureExplainerFlag(it) }
        },
        onConfirm = onConfirm,
        onCancel = onCancel
    )
}

@Composable
internal fun SettingsFeatureExplainerDialog(
    state: SettingsFeatureExplainerDialogState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var dontShowAgain by remember(visibleState) { mutableStateOf(false) }
    val exit = rememberSettingsDialogExitController(visibleState)

    fun finishDismiss() {
        exit.finish()
    }

    fun confirm() {
        exit.dismiss {
            if (dontShowAgain) visibleState.onDontShowAgain()
            visibleState.onConfirm()
            onDismiss()
        }
    }

    fun cancel() {
        exit.dismiss {
            if (dontShowAgain) visibleState.onDontShowAgain()
            visibleState.onCancel()
            onDismiss()
        }
    }

    fun dismissByScrim() {
        exit.dismiss {
            visibleState.onCancel()
            onDismiss()
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "FeatureExplainerDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = ::dismissByScrim,
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = visibleState.title,
                text = {
                    FeatureExplainerContent(
                        state = visibleState,
                        uiMode = uiMode,
                        dontShowAgain = dontShowAgain,
                        onDontShowAgainChange = { dontShowAgain = it },
                        modifier = Modifier.padding(bottom = SettingsLayoutMetrics.SheetBottomPadding)
                    )
                },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(
                                text = visibleState.cancelText,
                                onClick = ::cancel
                            ),
                            MaterialSettingsDialogAction(
                                text = visibleState.confirmText,
                                onClick = ::confirm
                            )
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = visibleState.title,
            onDismissRequest = ::dismissByScrim,
            onDismissFinished = ::finishDismiss
        ) {
            FeatureExplainerContent(
                state = visibleState,
                uiMode = uiMode,
                dontShowAgain = dontShowAgain,
                onDontShowAgainChange = { dontShowAgain = it },
                modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
            )
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = visibleState.cancelText,
                        onClick = ::cancel
                    ),
                    SettingsDialogAction(
                        text = visibleState.confirmText,
                        onClick = ::confirm,
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun FeatureExplainerContent(
    state: SettingsFeatureExplainerDialogState,
    uiMode: BibiUiMode,
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        FeatureExplainerText(
            text = state.directionText,
            uiMode = uiMode,
            accent = true,
            strong = true
        )
        Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerSectionSpacing))
        FeatureDescriptionRow(
            label = state.fromLabel,
            description = state.fromDescription,
            uiMode = uiMode
        )
        val arrowTint = when (uiMode) {
            BibiUiMode.Material -> MaterialTheme.colorScheme.primary
            BibiUiMode.Miuix -> MiuixTheme.colorScheme.primary
        }
        MaterialIcon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = null,
            tint = arrowTint,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = SettingsLayoutMetrics.FeatureExplainerIconVerticalPadding)
        )
        FeatureDescriptionRow(
            label = state.toLabel,
            description = state.toDescription,
            uiMode = uiMode,
            strongDescription = true
        )
        state.dontShowAgainText?.let { text ->
            Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerDontShowSpacing))
            DontShowAgainRow(
                text = text,
                uiMode = uiMode,
                checked = dontShowAgain,
                onCheckedChange = onDontShowAgainChange
            )
        }
    }
}

@Composable
private fun FeatureDescriptionRow(
    label: String,
    description: String,
    uiMode: BibiUiMode,
    strongDescription: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        FeatureExplainerText(
            text = label,
            uiMode = uiMode,
            secondary = true
        )
        Spacer(modifier = Modifier.width(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
        FeatureExplainerText(
            text = description,
            uiMode = uiMode,
            strong = strongDescription,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DontShowAgainRow(
    text: String,
    uiMode: BibiUiMode,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    fun changeWithHaptic(value: Boolean) {
        hapticTap()
        onCheckedChange(value)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { changeWithHaptic(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (uiMode) {
            BibiUiMode.Material -> MaterialCheckbox(
                checked = checked,
                onCheckedChange = ::changeWithHaptic
            )

            BibiUiMode.Miuix -> MiuixCheckbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = { changeWithHaptic(!checked) }
            )
        }
        Spacer(modifier = Modifier.width(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
        FeatureExplainerText(
            text = text,
            uiMode = uiMode,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FeatureExplainerText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    secondary: Boolean = false,
    accent: Boolean = false
) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = modifier,
            color = when {
                accent -> MaterialTheme.colorScheme.primary
                secondary -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            style = if (strong) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = when {
                accent -> MiuixTheme.colorScheme.primary
                secondary -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.onSurface
            },
            style = if (strong) {
                MiuixTheme.textStyles.title4
            } else {
                MiuixTheme.textStyles.body2
            },
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun Context.hasFeatureExplainerFlag(key: String): Boolean {
    val prefs = getSharedPreferences(FEATURE_EXPLAINER_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(key, false)
}

private fun Context.saveFeatureExplainerFlag(key: String) {
    val prefs = getSharedPreferences(FEATURE_EXPLAINER_PREFS, Context.MODE_PRIVATE)
    prefs.edit().putBoolean(key, true).apply()
}
