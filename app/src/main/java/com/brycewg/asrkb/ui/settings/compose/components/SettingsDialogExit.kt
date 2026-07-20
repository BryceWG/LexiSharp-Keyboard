/**
 * Compose 设置/历史弹窗退出动画公共控制器。
 *
 * Material：alpha 淡出后由 LaunchedEffect 触发 finish。
 * Miuix：将 show 交给 OverlayDialog，由其 onDismissFinished 触发 finish。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

internal const val SETTINGS_DIALOG_EXIT_MILLIS = 180

@Stable
internal class SettingsDialogExitController {
    var show by mutableStateOf(true)
        private set

    private var afterDismiss: (() -> Unit)? = null

    fun dismiss(action: (() -> Unit)? = null) {
        if (!show) return
        afterDismiss = action
        show = false
    }

    fun finish() {
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
    }
}

@Composable
internal fun rememberSettingsDialogExitController(
    key: Any? = Unit
): SettingsDialogExitController = remember(key) { SettingsDialogExitController() }

@Composable
internal fun animateSettingsDialogExitAlpha(
    show: Boolean,
    label: String = "SettingsDialogExitAlpha"
): Float {
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(SETTINGS_DIALOG_EXIT_MILLIS),
        label = label
    )
    return alpha
}

@Composable
internal fun MaterialSettingsDialogExitEffect(
    show: Boolean,
    onFinished: () -> Unit
) {
    LaunchedEffect(show) {
        if (!show) {
            delay(SETTINGS_DIALOG_EXIT_MILLIS.toLong())
            onFinished()
        }
    }
}
