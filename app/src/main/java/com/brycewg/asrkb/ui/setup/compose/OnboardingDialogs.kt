/**
 * 新手引导 Compose 弹窗与下载源选择。
 *
 * 归属模块：ui/setup/compose
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.setup.compose

import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDownloadSourceSheet
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun OnboardingDialogHost(
    state: OnboardingDialogState,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onConfirmOnlineGuide: () -> Unit,
    onSelectDownloadSource: (DownloadSourceOption) -> Unit
) {
    when (state) {
        OnboardingDialogState.None -> Unit
        OnboardingDialogState.OnlineGuide -> OnlineGuideDialog(
            uiMode = uiMode,
            onDismiss = onDismiss,
            onConfirm = onConfirmOnlineGuide
        )

        is OnboardingDialogState.DownloadSources -> SettingsDownloadSourceSheet(
            options = state.options,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onSelect = onSelectDownloadSource
        )
    }
}

@Composable
private fun OnlineGuideDialog(
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.model_guide_option_online)
    val message = stringResource(R.string.model_guide_online_dialog_message)
    val confirm = stringResource(R.string.btn_get_api_key_guide)
    val cancel = stringResource(R.string.btn_cancel)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "OnlineGuideDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = { MaterialText(message) },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(cancel, onClick = { exit.dismiss(onDismiss) }),
                            MaterialSettingsDialogAction(confirm, onClick = { exit.dismiss(onConfirm) })
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            summary = message,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = cancel,
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = confirm,
                        onClick = { exit.dismiss(onConfirm) },
                        primary = true
                    )
                )
            )
        }
    }
}
