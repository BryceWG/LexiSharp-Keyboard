/**
 * Compose 设置页轻量说明弹窗：多段正文 + 可选「下次不再提醒」。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsNoticeDialogState(
    val title: String,
    val paragraphs: List<String>,
    val dontShowAgainText: String?,
    val confirmText: String?,
    val dismissText: String,
    val onDontShowAgain: () -> Unit,
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {}
)

@Composable
internal fun SettingsNoticeDialog(
    state: SettingsNoticeDialogState?,
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

    val actions = buildList {
        if (visibleState.confirmText != null) {
            add(
                SettingsDialogAction(
                    text = visibleState.dismissText,
                    onClick = ::cancel
                )
            )
            add(
                SettingsDialogAction(
                    text = visibleState.confirmText,
                    onClick = ::confirm,
                    primary = true
                )
            )
        } else {
            add(
                SettingsDialogAction(
                    text = visibleState.dismissText,
                    onClick = ::cancel,
                    primary = true
                )
            )
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "NoticeDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = ::dismissByScrim,
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = visibleState.title,
                text = {
                    NoticeDialogContent(
                        state = visibleState,
                        uiMode = uiMode,
                        dontShowAgain = dontShowAgain,
                        onDontShowAgainChange = { dontShowAgain = it },
                        modifier = Modifier.padding(bottom = SettingsLayoutMetrics.SheetBottomPadding)
                    )
                },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = actions.map {
                            MaterialSettingsDialogAction(
                                text = it.text,
                                onClick = it.onClick,
                                enabled = it.enabled,
                                primary = it.primary
                            )
                        }
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
            NoticeDialogContent(
                state = visibleState,
                uiMode = uiMode,
                dontShowAgain = dontShowAgain,
                onDontShowAgainChange = { dontShowAgain = it },
                modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
            )
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = actions
            )
        }
    }
}

@Composable
private fun NoticeDialogContent(
    state: SettingsNoticeDialogState,
    uiMode: BibiUiMode,
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val paragraphs = state.paragraphs.filter { it.isNotBlank() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerSectionSpacing))
            }
            NoticeBodyText(text = paragraph, uiMode = uiMode)
        }
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
private fun NoticeBodyText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier
) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Normal
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Normal
        )
    }
}
