/**
 * Pro 购买页与开源版/Pro 版功能对比。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.ProPromoDialog
import com.brycewg.asrkb.ui.settings.compose.components.DialogTextAction
import com.brycewg.asrkb.ui.settings.compose.components.DialogTonalAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProPaywallScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    var showPaymentQr by remember { mutableStateOf(false) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    LaunchedEffect(appContext) { Prefs(appContext).proPromoShown = true }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.pro_paywall_title,
        onBack = onBack
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("hero") { PaywallHero(uiMode) }
            item("comparison") { PaywallComparison(uiMode) }
            item("purchase") {
                PaywallPurchase(
                    uiMode = uiMode,
                    onPlayStore = { ProPromoDialog.openPlayStore(context)?.let { messageDialog = message(it, context) } },
                    onPaymentQr = { showPaymentQr = true },
                    onTelegram = { ProPromoDialog.openTelegram(context)?.let { messageDialog = message(it, context) } }
                )
            }
        }
    }

    if (showPaymentQr) {
        com.brycewg.asrkb.ui.settings.compose.components.ProPromoDialogHost(
            state = com.brycewg.asrkb.ui.settings.compose.components.ProPromoDialogUiState.PaymentQr,
            uiMode = uiMode,
            onStateChange = { showPaymentQr = false }
        )
    }
    SettingsMessageDialog(state = messageDialog, uiMode = uiMode, onDismiss = { messageDialog = null })
}

private fun message(res: Int, context: android.content.Context) = SettingsMessageDialogState(
    title = context.getString(R.string.pro_paywall_title),
    message = context.getString(res),
    confirmText = context.getString(android.R.string.ok)
)

@Composable
private fun PaywallHero(uiMode: BibiUiMode) {
    SettingsSectionContainer(uiMode = uiMode) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.pro_paywall_eyebrow),
                style = when (uiMode) {
                    BibiUiMode.Material -> MaterialTheme.typography.labelLarge
                    BibiUiMode.Miuix -> MiuixTheme.textStyles.footnote1
                },
                color = when (uiMode) {
                    BibiUiMode.Material -> MaterialTheme.colorScheme.primary
                    BibiUiMode.Miuix -> MiuixTheme.colorScheme.primary
                },
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            PaywallText(stringResource(R.string.pro_paywall_heading), uiMode, title = true)
            Spacer(Modifier.height(8.dp))
            PaywallText(stringResource(R.string.pro_paywall_subtitle), uiMode, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PaywallText(stringResource(R.string.pro_paywall_price), uiMode, title = true)
            PaywallText(stringResource(R.string.pro_paywall_price_note), uiMode, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PaywallComparison(uiMode: BibiUiMode) {
    SettingsSectionContainer(uiMode = uiMode, titleRes = R.string.pro_paywall_compare_title) {
        ComparisonHeader(uiMode)
        comparisonRows.forEach { row -> ComparisonRow(row, uiMode) }
    }
}

@Composable
private fun ComparisonHeader(uiMode: BibiUiMode) {
    ComparisonRow(
        ComparisonRowData(R.string.pro_paywall_feature_header, false, false),
        uiMode,
        header = true
    )
}

@Composable
private fun ComparisonRow(row: ComparisonRowData, uiMode: BibiUiMode, header: Boolean = false) {
    val background = if (header) {
        when (uiMode) {
            BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceVariant
            BibiUiMode.Miuix -> MiuixTheme.colorScheme.secondaryVariant
        }
    } else {
        null
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(background ?: androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PaywallText(stringResource(row.feature), uiMode, modifier = Modifier.weight(1f), strong = header)
        ComparisonValue(row.free, uiMode, header, R.string.pro_paywall_free_header)
        ComparisonValue(row.pro, uiMode, header, R.string.pro_paywall_pro_header)
    }
}

@Composable
private fun ComparisonValue(enabled: Boolean, uiMode: BibiUiMode, header: Boolean, label: Int) {
    val availability = if (enabled) {
        stringResource(R.string.pro_paywall_included)
    } else {
        stringResource(R.string.pro_paywall_not_included)
    }
    PaywallText(
        text = if (header) {
            stringResource(label)
        } else {
            stringResource(
                if (enabled) R.string.pro_paywall_included_mark else R.string.pro_paywall_not_included_mark
            )
        },
        uiMode = uiMode,
        modifier = Modifier
            .width(56.dp)
            .then(if (header) Modifier else Modifier.semantics { contentDescription = availability }),
        textAlign = TextAlign.Center,
        strong = header
    )
}

@Composable
private fun PaywallPurchase(uiMode: BibiUiMode, onPlayStore: () -> Unit, onPaymentQr: () -> Unit, onTelegram: () -> Unit) {
    SettingsSectionContainer(uiMode = uiMode, titleRes = R.string.pro_paywall_purchase_title) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ActionButtonSpacing)
        ) {
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(R.string.pro_paywall_play_store),
                leadingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = onPlayStore,
                modifier = Modifier.fillMaxWidth()
            )
            DialogTonalAction(
                text = stringResource(R.string.pro_paywall_qr),
                uiMode = uiMode,
                onClick = onPaymentQr
            )
            DialogTextAction(
                text = stringResource(R.string.pro_paywall_telegram),
                uiMode = uiMode,
                onClick = onTelegram
            )
            PaywallText(stringResource(R.string.pro_paywall_purchase_note), uiMode, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PaywallText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    title: Boolean = false,
    strong: Boolean = false,
    textAlign: TextAlign? = null
) {
    val style = when (uiMode) {
        BibiUiMode.Material -> if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium
        BibiUiMode.Miuix -> if (title) MiuixTheme.textStyles.title3 else MiuixTheme.textStyles.body2
    }
    val color = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.onSurface
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
    }
    when (uiMode) {
        BibiUiMode.Material -> Text(text, modifier, color, style = style, fontWeight = if (strong) FontWeight.SemiBold else null, textAlign = textAlign)
        BibiUiMode.Miuix -> MiuixText(text = text, modifier = modifier, color = color, style = style, fontWeight = if (strong) FontWeight.SemiBold else null, textAlign = textAlign)
    }
}

private data class ComparisonRowData(
    val feature: Int,
    val free: Boolean,
    val pro: Boolean
)

private val comparisonRows = listOf(
    ComparisonRowData(R.string.pro_paywall_feature_core_asr, true, true),
    ComparisonRowData(R.string.pro_paywall_feature_ai, true, true),
    ComparisonRowData(R.string.pro_paywall_feature_input, true, true),
    ComparisonRowData(R.string.pro_paywall_feature_layout, true, true),
    ComparisonRowData(R.string.pro_paywall_feature_continuous, false, true),
    ComparisonRowData(R.string.pro_paywall_feature_hotwords, false, true),
    ComparisonRowData(R.string.pro_paywall_feature_context, false, true),
    ComparisonRowData(R.string.pro_paywall_feature_text, false, true),
    ComparisonRowData(R.string.pro_paywall_feature_theme, false, true),
    ComparisonRowData(R.string.pro_paywall_feature_webdav, false, true)
)
