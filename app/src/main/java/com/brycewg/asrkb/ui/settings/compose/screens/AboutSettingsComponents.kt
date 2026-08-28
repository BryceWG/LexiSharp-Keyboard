/**
 * Compose 关于页的纯 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.about_title,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun AboutSection(
    uiMode: BibiUiMode,
    titleRes: Int? = null,
    contentPadding: PaddingValues = PaddingValues(
        top = SettingsLayoutMetrics.AboutSectionContentTopPadding,
        bottom = SettingsLayoutMetrics.AboutSectionContentBottomPadding
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionContainer(
        uiMode = uiMode,
        titleRes = titleRes
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
internal fun AboutAppIntro(
    appName: String,
    version: String,
    packageName: String,
    description: String,
    uiMode: BibiUiMode
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AboutText(appName, uiMode, strong = true)
                AboutText(version, uiMode)
                AboutText(packageName, uiMode)
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Adaptive icon foreground 内含安全区留白；放大绘制并裁到占位，让 logo 更饱满。
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = appName,
                    modifier = Modifier
                        .size(80.dp)
                        .scale(1.55f)
                )
            }
        }
        AboutText(description, uiMode)
    }
}

@Composable
internal fun AboutText(
    text: String,
    uiMode: BibiUiMode,
    strong: Boolean = false
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            color = if (strong) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
internal fun AboutDivider(uiMode: BibiUiMode) {
    if (uiMode == BibiUiMode.Material) {
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun AboutAcknowledgement(
    titleRes: Int,
    descRes: Int,
    uiMode: BibiUiMode
) {
    AboutText(stringResource(titleRes), uiMode, strong = true)
    AboutText(stringResource(descRes), uiMode)
}
