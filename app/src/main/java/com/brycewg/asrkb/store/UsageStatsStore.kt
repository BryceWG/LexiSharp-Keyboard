package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.asr.AsrVendor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 使用统计聚合逻辑（从 [Prefs] 中拆出）。
 *
 * 存储：
 * - `usage_stats`：聚合 JSON（kotlinx.serialization）
 * - `first_use_date`：yyyyMMdd
 */
internal object UsageStatsStore {
    private const val TAG = "Prefs"

    fun getUsageStats(prefs: Prefs, json: Json): UsageStats {
        val usageStatsJson = prefs.getPrefString(KEY_USAGE_STATS_JSON, "")
        if (usageStatsJson.isBlank()) {
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            if (prefs.firstUseDate.isBlank()) prefs.firstUseDate = today
            return UsageStats(firstUseDate = prefs.firstUseDate)
        }
        return try {
            val stats = json.decodeFromString<UsageStats>(usageStatsJson)
            // 兼容老数据：填充 firstUseDate
            if (stats.firstUseDate.isBlank()) {
                val fud = prefs.firstUseDate.ifBlank { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) }
                stats.firstUseDate = fud
                setUsageStats(prefs, json, stats)
            }
            stats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UsageStats JSON", e)
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            if (prefs.firstUseDate.isBlank()) prefs.firstUseDate = today
            UsageStats(firstUseDate = prefs.firstUseDate)
        }
    }

    private fun setUsageStats(prefs: Prefs, json: Json, stats: UsageStats) {
        try {
            prefs.setPrefString(KEY_USAGE_STATS_JSON, json.encodeToString(stats))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize UsageStats", e)
        }
    }

    /**
     * 清空使用统计聚合与总字数。
     * 注：firstUseDate 不清空，以保持“陪伴天数”展示的连续性。
     */
    fun resetUsageStats(prefs: Prefs) {
        try {
            prefs.setPrefString(KEY_USAGE_STATS_JSON, "")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset usage stats", t)
        }
        try {
            prefs.totalAsrChars = 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset totalAsrChars", t)
        }
    }

    /**
     * 记录一次“最终提交”的使用统计（仅在有最终文本提交时调用）。
     * @param source 用途来源："ime" / "floating" / "aiEdit"（当前仅 ime 与 floating 计入平均值）
     * @param vendor 供应商（用于 perVendor 聚合）
     * @param audioMs 本次会话的录音时长（毫秒）
     * @param chars 提交的字符数
     */
    fun recordUsageCommit(
        prefs: Prefs,
        json: Json,
        source: String,
        vendor: AsrVendor,
        audioMs: Long,
        chars: Int,
        procMs: Long = 0L,
    ) {
        if (chars <= 0 && audioMs <= 0) return
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val stats = getUsageStats(prefs, json)

        stats.totalSessions += 1
        stats.totalChars += chars.coerceAtLeast(0)
        stats.totalAudioMs += audioMs.coerceAtLeast(0L)
        stats.totalProcMs += procMs.coerceAtLeast(0L)

        val key = vendor.id
        val va = stats.perVendor[key] ?: VendorAgg()
        va.sessions += 1
        va.chars += chars.coerceAtLeast(0)
        va.audioMs += audioMs.coerceAtLeast(0L)
        va.procMs += procMs.coerceAtLeast(0L)
        stats.perVendor[key] = va

        val da = stats.daily[today] ?: DayAgg()
        da.sessions += 1
        da.chars += chars.coerceAtLeast(0)
        da.audioMs += audioMs.coerceAtLeast(0L)
        da.procMs += procMs.coerceAtLeast(0L)
        stats.daily[today] = da

        // 裁剪 daily 至最近 400 天（防止无限增长）
        try {
            if (stats.daily.size > 400) {
                val keys = stats.daily.keys.sorted()
                val toDrop = keys.size - 400
                keys.take(toDrop).forEach { stats.daily.remove(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to prune daily stats", t)
        }

        setUsageStats(prefs, json, stats)
        val syncedTotalChars = stats.totalChars.coerceAtLeast(0)
        if (syncedTotalChars > prefs.totalAsrChars) {
            prefs.totalAsrChars = syncedTotalChars
        }
    }

    /**
     * 计算“陪伴天数”。若缺少 firstUseDate，以今天为首次使用（=1天）。
     */
    fun getDaysSinceFirstUse(prefs: Prefs): Long {
        val fud = prefs.firstUseDate.ifBlank {
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            prefs.firstUseDate = today
            today
        }
        return try {
            val start = LocalDate.parse(fud, DateTimeFormatter.BASIC_ISO_DATE)
            val now = LocalDate.now()
            ChronoUnit.DAYS.between(start, now) + 1 // 含当天
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse firstUseDate '$fud'", e)
            1
        }
    }
}

@Serializable
data class VendorAgg(
    var sessions: Long = 0,
    var chars: Long = 0,
    var audioMs: Long = 0,
    // 非流式请求的供应商处理耗时聚合（毫秒）
    var procMs: Long = 0,
)

@Serializable
data class DayAgg(
    var sessions: Long = 0,
    var chars: Long = 0,
    var audioMs: Long = 0,
    var procMs: Long = 0,
)

@Serializable
data class UsageStats(
    var totalSessions: Long = 0,
    var totalChars: Long = 0,
    var totalAudioMs: Long = 0,
    var totalProcMs: Long = 0,
    var perVendor: MutableMap<String, VendorAgg> = mutableMapOf(),
    var daily: MutableMap<String, DayAgg> = mutableMapOf(),
    var firstUseDate: String = "",
)
