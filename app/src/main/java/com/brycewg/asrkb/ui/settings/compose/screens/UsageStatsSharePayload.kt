/**
 * 使用统计分享卡的数据载荷与构建。
 *
 * 负责把 [com.brycewg.asrkb.store.UsageStats] 聚合为卡片可直接渲染的结构，
 * 并派生“可晒”的估算数据：语音输入相对手打节省的时间。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.UsageStats
import com.brycewg.asrkb.ui.AsrVendorUi
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.roundToLong

internal data class UsageStatsShareMetric(
    val label: String,
    val value: String
)

internal data class UsageStatsShareDailyBar(
    val weekday: String,
    val date: String,
    val chars: Long,
    val valueText: String,
    val ratio: Float,
    val isMax: Boolean
)

internal data class UsageStatsShareVendorRow(
    val name: String,
    val valueText: String,
    val ratio: Float
)

internal data class UsageStatsSharePayload(
    val appDisplayName: String,
    val tagline: String,
    val subtitle: String,
    val generatedAt: String,
    val heroDaysValue: String,
    val heroDaysUnit: String,
    val heroCaption: String,
    val heroSavedText: String?,
    val metrics: List<UsageStatsShareMetric>,
    val last7DaysTitle: String,
    val dailyBars: List<UsageStatsShareDailyBar>,
    val topVendorsTitle: String,
    val topVendors: List<UsageStatsShareVendorRow>,
    val emptyPlaceholder: String,
    val footerSite: String,
    val hasChartData: Boolean,
    val hasVendorData: Boolean
)

/** 手打速度假设：约 50 字/分钟，用于估算语音输入节省的打字时间 */
private const val ASSUMED_TYPING_CHARS_PER_MIN = 50.0

/** 累计字数达到该量级才展示“节省打字时间”，避免几分钟的琐碎数字 */
private const val MIN_CHARS_FOR_TIME_SAVED = 200L

internal fun buildUsageStatsSharePayload(context: Context, prefs: Prefs): UsageStatsSharePayload {
    val stats = prefs.getUsageStats()
    val sessions = stats.totalSessions.coerceAtLeast(0)
    val totalChars = stats.totalChars.coerceAtLeast(0)
    val totalAudioMs = stats.totalAudioMs.coerceAtLeast(0)
    val daysWithYou = prefs.getDaysSinceFirstUse().coerceAtLeast(1)

    val dailyBars = buildShareDailyBars(context, stats, days = 7)
    val topVendors = buildShareTopVendors(context, stats, limit = 3)
    val generatedAt = try {
        DateFormat.getDateInstance(DateFormat.MEDIUM, LocaleHelper.locale(context)).format(Date())
    } catch (t: Throwable) {
        Log.w("UsageStatsShare", "Failed to format share-card date", t)
        LocalDate.now().toString()
    }

    val avgSpeedFormatted = if (sessions > 0 && totalAudioMs > 0) {
        val avgSpeed = totalChars * 60_000.0 / totalAudioMs.toDouble()
        context.getString(
            R.string.about_stats_avg_speed_value,
            String.format(LocaleHelper.locale(context), "%.1f", avgSpeed)
        )
    } else {
        context.getString(R.string.about_empty_stats_placeholder)
    }

    return UsageStatsSharePayload(
        appDisplayName = context.getString(R.string.app_name),
        tagline = context.getString(R.string.about_stats_share_tagline),
        subtitle = context.getString(R.string.about_stats_share_subtitle),
        generatedAt = generatedAt,
        heroDaysValue = formatShareInt(context, daysWithYou),
        heroDaysUnit = context.getString(R.string.about_stats_share_days_unit),
        heroCaption = context.getString(
            R.string.about_stats_share_hero_caption,
            context.getString(R.string.app_name)
        ),
        heroSavedText = buildTimeSavedText(context, totalChars, totalAudioMs),
        metrics = listOf(
            UsageStatsShareMetric(
                label = context.getString(R.string.about_stats_total_chars_label),
                value = formatShareInt(context, totalChars)
            ),
            UsageStatsShareMetric(
                label = context.getString(R.string.about_stats_total_audio_label),
                value = context.formatShareDurationMs(totalAudioMs)
            ),
            UsageStatsShareMetric(
                label = context.getString(R.string.about_stats_sessions_label),
                value = formatShareInt(context, sessions)
            ),
            UsageStatsShareMetric(
                label = context.getString(R.string.about_stats_avg_speed_label),
                value = avgSpeedFormatted
            )
        ),
        last7DaysTitle = context.getString(R.string.about_last_7_days),
        dailyBars = dailyBars,
        topVendorsTitle = context.getString(R.string.about_stats_share_top_vendors),
        topVendors = topVendors,
        emptyPlaceholder = context.getString(R.string.about_empty_stats_placeholder),
        footerSite = context.getString(R.string.about_stats_share_footer_site),
        hasChartData = dailyBars.any { it.chars > 0 },
        hasVendorData = topVendors.isNotEmpty()
    )
}

/** 估算语音输入相对手打节省的时间，数据量不足时不展示 */
private fun buildTimeSavedText(context: Context, totalChars: Long, totalAudioMs: Long): String? {
    if (totalChars < MIN_CHARS_FOR_TIME_SAVED) return null
    val savedMs = (totalChars / ASSUMED_TYPING_CHARS_PER_MIN * 60_000.0).roundToLong() - totalAudioMs
    if (savedMs <= 0) return null
    return context.getString(
        R.string.about_stats_share_insight_time_saved,
        context.formatShareDurationMs(savedMs)
    )
}

private fun buildShareDailyBars(context: Context, stats: UsageStats, days: Int): List<UsageStatsShareDailyBar> {
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    val locale = LocaleHelper.locale(context)
    val weekdayFmt = DateTimeFormatter.ofPattern("E", locale)
    val dateFmt = DateTimeFormatter.ofPattern("MM-dd", locale)
    val entries = ArrayList<Triple<LocalDate, String, Long>>(days)
    var d = LocalDate.now().minusDays(days - 1L)
    repeat(days) {
        val key = d.format(fmt)
        entries.add(Triple(d, key, stats.daily[key]?.chars ?: 0L))
        d = d.plusDays(1)
    }
    val maxChars = entries.maxOfOrNull { it.third }?.coerceAtLeast(1L) ?: 1L
    return entries.map { (date, _, chars) ->
        UsageStatsShareDailyBar(
            weekday = date.format(weekdayFmt),
            date = date.format(dateFmt),
            chars = chars,
            valueText = formatShareInt(context, chars),
            ratio = (chars.toDouble() / maxChars.toDouble()).toFloat().coerceIn(0f, 1f),
            isMax = chars > 0 && chars == maxChars
        )
    }
}

private fun buildShareTopVendors(
    context: Context,
    stats: UsageStats,
    limit: Int
): List<UsageStatsShareVendorRow> {
    val vendorPairs = stats.perVendor
        .map { it.key to it.value }
        .filter { it.second.chars > 0 }
        .sortedByDescending { it.second.chars }
        .take(limit)
    if (vendorPairs.isEmpty()) return emptyList()
    val totalVendorChars = stats.perVendor.values.sumOf { it.chars }.coerceAtLeast(1L)
    return vendorPairs.map { (id, agg) ->
        val percent = (agg.chars * 100.0 / totalVendorChars.toDouble()).roundToLong()
        UsageStatsShareVendorRow(
            name = AsrVendorUi.name(context, AsrVendor.fromId(id)),
            valueText = buildString {
                append(formatShareInt(context, agg.chars)).append(' ')
                append(context.getString(R.string.unit_chars))
                append(" · ")
                append(String.format(LocaleHelper.locale(context), "%d%%", percent))
            },
            ratio = (agg.chars.toDouble() / totalVendorChars.toDouble()).toFloat().coerceIn(0f, 1f)
        )
    }
}

private fun Context.formatShareDurationMs(ms: Long): String {
    if (ms <= 0) return getString(R.string.unit_0_min)
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hr = min / 60
    return when {
        hr > 0 -> getString(R.string.fmt_h_m, hr, (min % 60))
        min > 0 -> getString(R.string.fmt_m_s, min, sec)
        else -> getString(R.string.fmt_s, sec)
    }
}

private fun formatShareInt(context: Context, v: Long): String =
    NumberFormat.getIntegerInstance(LocaleHelper.locale(context)).format(v)
