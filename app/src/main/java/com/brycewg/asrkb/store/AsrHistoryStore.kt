package com.brycewg.asrkb.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * ASR 历史记录存储
 * - 使用 SharedPreferences(JSON) 存储，纳入现有备份导入/导出范围（Prefs KEY_ASR_HISTORY_JSON）
 * - 提供新增、查询、删除（单个/批量）
 */
class AsrHistoryStore(context: Context) {
    companion object {
        private const val TAG = "AsrHistoryStore"
        private const val SP_NAME = "asr_prefs"
        private const val KEY_ASR_HISTORY_JSON = "asr_history"

        // 防止无限增长，保留最近 N 条
        private const val MAX_RECORDS = 2000
    }

    private val sp: SharedPreferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Serializable
    enum class AiPostStatus {
        NONE,
        SUCCESS,
        FAILED,
    }

    @Serializable
    data class AsrHistoryRecord(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long,
        val text: String,
        val vendorId: String,
        val audioMs: Long,
        // 端到端总耗时（毫秒）：从开始录音到最终提交完成（含识别/后处理/打字机动画等待等）。
        // 旧记录无该字段时视为 0。
        val totalElapsedMs: Long = 0,
        // 供应商处理耗时（非流式文件识别时有效，毫秒）。OSS 旧记录无该字段时视为 0。
        val procMs: Long = 0,
        // "ime" | "floating" | "external"
        val source: String,
        val aiProcessed: Boolean,
        // AI 后处理耗时（毫秒）。未尝试或旧记录无该字段时视为 0。
        val aiPostMs: Long = 0,
        // AI 后处理状态。旧记录无该字段时视为 NONE。
        val aiPostStatus: AiPostStatus = AiPostStatus.NONE,
        val charCount: Int,
    )

    private fun readAllInternal(): MutableList<AsrHistoryRecord> {
        val raw = sp.getString(KEY_ASR_HISTORY_JSON, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<AsrHistoryRecord>>(raw).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse history JSON", e)
            mutableListOf()
        }
    }

    private fun writeAllInternal(list: List<AsrHistoryRecord>) {
        try {
            val text = json.encodeToString(list)
            sp.edit().putString(KEY_ASR_HISTORY_JSON, text).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write history JSON", e)
        }
    }

    fun add(record: AsrHistoryRecord) {
        val list = readAllInternal()
        list.add(record)
        // 按时间倒序裁剪
        val ordered = list.sortedByDescending { it.timestamp }
        val pruned = if (ordered.size > MAX_RECORDS) ordered.take(MAX_RECORDS) else ordered
        writeAllInternal(pruned)
    }

    fun listAll(): List<AsrHistoryRecord> = readAllInternal().sortedByDescending { it.timestamp }

    fun deleteByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val list = readAllInternal()
        val before = list.size
        val remained = list.filterNot { ids.contains(it.id) }
        writeAllInternal(remained)
        return (before - remained.size).coerceAtLeast(0)
    }

    fun clearAll() {
        writeAllInternal(emptyList())
    }
}
