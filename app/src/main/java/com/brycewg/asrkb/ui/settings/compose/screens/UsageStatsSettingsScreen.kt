/**
 * Compose 使用统计设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton

@Composable
fun UsageStatsSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) { Prefs(appContext) }
    val scope = rememberCoroutineScope()
    val hapticTap = LocalSettingsHapticTap.current
    var usageInfo by remember(appContext) { mutableStateOf<AboutUsageInfo?>(null) }
    var shareBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var shareBusy by remember { mutableStateOf(false) }

    LaunchedEffect(appContext, prefs) {
        usageInfo = withContext(Dispatchers.IO) {
            buildUsageInfo(appContext, prefs)
        }
    }

    fun openSharePreview() {
        if (shareBusy) return
        shareBusy = true
        hapticTap()
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    val payload = buildUsageStatsSharePayload(appContext, prefs)
                    UsageStatsShareCardRenderer.render(appContext, payload)
                }
                shareBitmap = bitmap
            } catch (t: Throwable) {
                android.util.Log.e("UsageStatsShare", "Failed to render usage stats share card", t)
                Toast.makeText(
                    context,
                    R.string.about_stats_share_failed,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                shareBusy = false
            }
        }
    }

    UsageStatsSharePreviewDialog(
        bitmap = shareBitmap,
        uiMode = uiMode,
        onDismiss = { shareBitmap = null }
    )

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.about_stats_title,
        onBack = onBack,
        actions = {
            val shareLabel = stringResource(R.string.about_stats_share)
            when (uiMode) {
                BibiUiMode.Material -> IconButton(
                    onClick = ::openSharePreview,
                    enabled = !shareBusy
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = shareLabel
                    )
                }

                BibiUiMode.Miuix -> MiuixIconButton(
                    onClick = {
                        if (!shareBusy) openSharePreview()
                    }
                ) {
                    MiuixIcon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = shareLabel
                    )
                }
            }
        }
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            val info = usageInfo
            item("overview") {
                UsageStatsSection(
                    uiMode = uiMode,
                    titleRes = R.string.about_stats_overview,
                    highlightId = "about_stats_overview"
                ) {
                    if (info == null) {
                        UsageStatsEmptyText(uiMode = uiMode)
                    } else {
                        UsageStatsHeroText(
                            text = stringResource(R.string.about_days_with_you, info.daysWithYou),
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_total_audio_label,
                            value = info.totalAudioFormatted,
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_total_chars_label,
                            value = info.totalCharsFormatted,
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_sessions_label,
                            value = info.totalSessionsFormatted,
                            uiMode = uiMode
                        )
                        AboutDivider(uiMode = uiMode)
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_avg_audio_label,
                            value = info.avgAudioFormatted,
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_avg_chars_label,
                            value = info.avgCharsFormatted,
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_avg_speed_label,
                            value = info.avgSpeedFormatted,
                            uiMode = uiMode
                        )
                        AboutDivider(uiMode = uiMode)
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_daily_avg_7d_label,
                            value = info.dailyAvg7dFormatted,
                            uiMode = uiMode
                        )
                        UsageStatsMetricRow(
                            labelRes = R.string.about_stats_weekly_avg_4w_label,
                            value = info.weeklyAvg4wFormatted,
                            uiMode = uiMode
                        )
                    }
                }
            }

            item("daily") {
                UsageStatsSection(
                    uiMode = uiMode,
                    titleRes = R.string.about_last_7_days,
                    highlightId = "about_last_7_days"
                ) {
                    UsageStatsProgressList(
                        items = info?.dailyItems.orEmpty(),
                        uiMode = uiMode
                    )
                }
            }

            item("failure") {
                UsageStatsSection(
                    uiMode = uiMode,
                    titleRes = R.string.about_online_asr_failure_title,
                    highlightId = "about_online_asr_failure_title"
                ) {
                    UsageStatsProgressList(
                        items = info?.failureItems.orEmpty(),
                        uiMode = uiMode
                    )
                }
            }

            item("vendor") {
                UsageStatsSection(
                    uiMode = uiMode,
                    titleRes = R.string.about_by_vendor,
                    highlightId = "about_by_vendor"
                ) {
                    UsageStatsProgressList(
                        items = info?.vendorItems.orEmpty(),
                        uiMode = uiMode
                    )
                }
            }
        }
    }
}
