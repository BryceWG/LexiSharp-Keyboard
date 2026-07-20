/**
 * Compose Pro 弹窗宿主。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.ProPromoDialog
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal sealed interface ProPromoDialogUiState {
    data object Hidden : ProPromoDialogUiState
    data object Promo : ProPromoDialogUiState
    data object PaymentQr : ProPromoDialogUiState
}

@Composable
internal fun ProPromoDialogHost(
    state: ProPromoDialogUiState,
    uiMode: BibiUiMode,
    onStateChange: (ProPromoDialogUiState) -> Unit
) {
    val context = LocalContext.current
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var paneVisible by remember(state) { mutableStateOf(state != ProPromoDialogUiState.Hidden) }
    var pendingState by remember(state) { mutableStateOf<ProPromoDialogUiState?>(null) }

    fun dismissTo(nextState: ProPromoDialogUiState) {
        pendingState = nextState
        paneVisible = false
    }

    fun finishDismiss() {
        pendingState?.let(onStateChange)
        pendingState = null
    }

    fun showPromoMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.pro_promo_title),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showPaymentMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.payment_qr_title),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    when (state) {
        ProPromoDialogUiState.Hidden -> Unit
        ProPromoDialogUiState.Promo -> ProPromoDialogPane(
            uiMode = uiMode,
            show = paneVisible,
            onDismiss = { dismissTo(ProPromoDialogUiState.Hidden) },
            onDismissFinished = ::finishDismiss,
            content = {
                ProPromoDialogContent(
                    uiMode = uiMode,
                    actions = ProPromoDialogActions(
                        onPlayStore = {
                            ProPromoDialog.openPlayStore(context)?.let(::showPromoMessage)
                        },
                        onTelegram = {
                            ProPromoDialog.openTelegram(context)?.let(::showPromoMessage)
                        },
                        onPaymentQr = { onStateChange(ProPromoDialogUiState.PaymentQr) },
                        onClose = { dismissTo(ProPromoDialogUiState.Hidden) }
                    )
                )
            }
        )

        ProPromoDialogUiState.PaymentQr -> ProPromoDialogPane(
            uiMode = uiMode,
            show = paneVisible,
            onDismiss = { dismissTo(ProPromoDialogUiState.Promo) },
            onDismissFinished = ::finishDismiss,
            content = {
                PaymentQrDialogContent(
                    uiMode = uiMode,
                    authorEmail = ProPromoDialog.authorEmail,
                    actions = PaymentQrDialogActions(
                        onSaveWechat = {
                            showPaymentMessage(
                                ProPromoDialog.saveQrCodeToGallery(
                                    context = context,
                                    drawableResId = R.drawable.qr_wechat_pay,
                                    fileName = "wechat_pay_qr"
                                )
                            )
                        },
                        onSaveAlipay = {
                            showPaymentMessage(
                                ProPromoDialog.saveQrCodeToGallery(
                                    context = context,
                                    drawableResId = R.drawable.qr_alipay,
                                    fileName = "alipay_qr"
                                )
                            )
                        },
                        onCopyEmail = {
                            ProPromoDialog.copyEmailToClipboard(context)?.let(::showPaymentMessage)
                        },
                        onClose = { dismissTo(ProPromoDialogUiState.Promo) }
                    )
                )
            }
        )
    }
    SettingsMessageDialog(
        state = messageDialog,
        uiMode = uiMode,
        onDismiss = { messageDialog = null }
    )
}

@Composable
private fun ProPromoDialogPane(
    uiMode: BibiUiMode,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha by animateFloatAsState(
                targetValue = if (show) 1f else 0f,
                animationSpec = tween(PRO_DIALOG_EXIT_MILLIS),
                label = "ProPromoDialogAlpha"
            )
            LaunchedEffect(show) {
                if (!show) {
                    delay(PRO_DIALOG_EXIT_MILLIS.toLong())
                    onDismissFinished()
                }
            }
            Dialog(onDismissRequest = onDismiss) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsLayoutMetrics.DialogPaneHorizontalPadding)
                        .graphicsLayer(alpha = alpha),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.widthIn(max = SettingsLayoutMetrics.DialogMaxWidth)) {
                        content()
                    }
                }
            }
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = show,
            onDismissRequest = onDismiss,
            onDismissFinished = onDismissFinished,
            insideMargin = DpSize(0.dp, 0.dp)
        ) {
            Box(
                modifier = Modifier.widthIn(max = SettingsLayoutMetrics.DialogMaxWidth),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

private const val PRO_DIALOG_EXIT_MILLIS = SETTINGS_DIALOG_EXIT_MILLIS
