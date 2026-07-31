/**
 * Compose 使用统计页的纯 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsHighlightContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UsageStatsSection(
    uiMode: BibiUiMode,
    @StringRes titleRes: Int,
    highlightId: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsHighlightContainer(
        entryId = highlightId,
        uiMode = uiMode
    ) {
        SettingsSectionContainer(uiMode = uiMode, titleRes = titleRes) {
            when (uiMode) {
                BibiUiMode.Material -> SettingsMaterialItemSurface(
                    index = 0,
                    count = 1
                ) {
                    Column(
                        modifier = Modifier.padding(
                            top = SettingsLayoutMetrics.AboutSectionContentTopPadding,
                            bottom = SettingsLayoutMetrics.AboutSectionContentBottomPadding
                        ),
                        content = content
                    )
                }

                BibiUiMode.Miuix -> Column(
                    modifier = Modifier.padding(
                        top = SettingsLayoutMetrics.AboutSectionContentTopPadding,
                        bottom = SettingsLayoutMetrics.AboutSectionContentBottomPadding
                    ),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun UsageStatsHeroText(
    text: String,
    uiMode: BibiUiMode
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MiuixTheme.textStyles.title4
        )
    }
}

@Composable
internal fun UsageStatsMetricRow(
    @StringRes labelRes: Int,
    value: String,
    uiMode: BibiUiMode
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = stringResource(labelRes),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = stringResource(labelRes),
                    modifier = Modifier.weight(1f),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1
                )
                MiuixText(
                    text = value,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun UsageStatsProgressList(
    items: List<AboutProgressItem>,
    uiMode: BibiUiMode
) {
    if (items.isEmpty()) {
        UsageStatsEmptyText(uiMode = uiMode)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            UsageStatsProgressItem(item = item, uiMode = uiMode)
        }
    }
}

@Composable
internal fun UsageStatsEmptyText(uiMode: BibiUiMode) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = stringResource(R.string.about_empty_stats_placeholder),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        BibiUiMode.Miuix -> MiuixText(
            text = stringResource(R.string.about_empty_stats_placeholder),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
private fun UsageStatsProgressItem(
    item: AboutProgressItem,
    uiMode: BibiUiMode
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (item.valueBelowTitle) {
            UsageStatsStackedTitleValue(item = item, uiMode = uiMode)
        } else {
            UsageStatsInlineTitleValue(item = item, uiMode = uiMode)
        }
        UsageStatsProgressIndicator(
            uiMode = uiMode,
            progress = item.ratio.toFloat().coerceIn(0f, 1f),
            isError = item.isError
        )
    }
}

@Composable
private fun UsageStatsStackedTitleValue(
    item: AboutProgressItem,
    uiMode: BibiUiMode
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = item.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MiuixText(
                    text = item.value,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UsageStatsInlineTitleValue(
    item: AboutProgressItem,
    uiMode: BibiUiMode
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.value,
                    color = if (item.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MiuixText(
                    text = item.value,
                    color = if (item.isError) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UsageStatsProgressIndicator(
    uiMode: BibiUiMode,
    progress: Float,
    isError: Boolean
) {
    val modifier = Modifier.fillMaxWidth()
    when (uiMode) {
        BibiUiMode.Material -> LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        BibiUiMode.Miuix -> MiuixLinearProgressIndicator(
            progress = progress,
            modifier = modifier,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = if (isError) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.primary
                }
            )
        )
    }
}
