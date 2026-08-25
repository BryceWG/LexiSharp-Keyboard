package com.brycewg.asrkb.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence

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
        private val HISTORY_LOCK = Any()
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
        FAILED
    }

    @Serializable
    enum class AsrHistoryStatus {
        SUCCESS,
        FAILED,
        CANCELLED
    }

    @Serializable
    enum class AsrHistoryFailStage {
        NONE,
        RECORDING,
        RECOGNITION
    }

    @Serializable
    data class AsrHistoryRecord(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long,
        val text: String,
        // ASR 引擎返回、进入任何末处理前的原始文本；旧记录没有该字段。
        val rawText: String? = null,
        val vendorId: String,
        val audioMs: Long,
        // 端到端总耗时（毫秒）：从开始录音到最终提交完成（含识别/后处理/打字机动画等待等）。
        // 旧记录无该字段时视为 0。
        val totalElapsedMs: Long = 0,
        // 供应商处理耗时（非流式文件识别时有效，毫秒）。OSS 旧记录无该字段时视为 0。
        val procMs: Long = 0,
        val source: String, // "ime" | "floating" | "external"
        val aiProcessed: Boolean,
        // AI 后处理耗时（毫秒）。未尝试或旧记录无该字段时视为 0。
        val aiPostMs: Long = 0,
        // AI 后处理状态。旧记录无该字段时视为 NONE。
        val aiPostStatus: AiPostStatus = AiPostStatus.NONE,
        // 当次实际尝试后处理使用的 LLM 渠道。旧记录没有该字段。
        val llmVendorId: String? = null,
        val charCount: Int,
        // 会话结果。旧记录无该字段时视为 SUCCESS。
        val status: AsrHistoryStatus = AsrHistoryStatus.SUCCESS,
        // 失败/取消发生的阶段。旧记录无该字段时视为 NONE。
        val failStage: AsrHistoryFailStage = AsrHistoryFailStage.NONE,
        // 稳定失败原因码，不存本地化文案。成功记录为 null。
        val failReasonCode: String? = null
    ) {
        val isUnsuccessful: Boolean
            get() = status != AsrHistoryStatus.SUCCESS
    }

    private fun readAllInternal(): MutableList<AsrHistoryRecord> {
        val raw = sp.getString(KEY_ASR_HISTORY_JSON, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<AsrHistoryRecord>>(raw)
                .sortedByDescending { it.timestamp }
                .distinctBy { it.id }
                .toMutableList()
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
        synchronized(HISTORY_LOCK) {
            val list = readAllInternal()
            list.removeAll { it.id == record.id }
            list.add(record)
            // 按时间倒序裁剪
            val ordered = list.sortedByDescending { it.timestamp }
            val pruned = if (ordered.size > MAX_RECORDS) ordered.take(MAX_RECORDS) else ordered
            writeAllInternal(pruned)
        }
    }

    fun updateById(
        id: String,
        transform: (AsrHistoryRecord) -> AsrHistoryRecord
    ): AsrHistoryRecord? {
        synchronized(HISTORY_LOCK) {
            val list = readAllInternal()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = transform(list[index]).copy(id = id)
            list[index] = updated
            writeAllInternal(list)
            return updated
        }
    }

    fun listAll(): List<AsrHistoryRecord> = synchronized(HISTORY_LOCK) {
        readAllInternal().sortedByDescending { it.timestamp }
    }

    /**
     * 按写入顺序流式取出最近 [limit] 条记录（含失败/取消；跳过空白成功记录）。
     * 磁盘 JSON 由 [writeAllInternal] 按 timestamp 降序保存，因此不必先反序列化整表。
     */
    fun listRecent(limit: Int): List<AsrHistoryRecord> {
        if (limit <= 0) return emptyList()
        return synchronized(HISTORY_LOCK) {
            val raw = sp.getString(KEY_ASR_HISTORY_JSON, "").orEmpty()
            if (raw.isBlank()) return@synchronized emptyList()
            try {
                raw.byteInputStream().use { input ->
                    collectRecentFromStream(input, limit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stream recent history, falling back to full parse", e)
                readAllInternal()
                    .asSequence()
                    .filter { it.shouldIncludeInRecent() }
                    .take(limit)
                    .toList()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun collectRecentFromStream(
        input: InputStream,
        limit: Int
    ): List<AsrHistoryRecord> {
        val seenIds = HashSet<String>(limit)
        val out = ArrayList<AsrHistoryRecord>(limit)
        for (record in json.decodeToSequence<AsrHistoryRecord>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
            if (!record.shouldIncludeInRecent()) continue
            if (!seenIds.add(record.id)) continue
            out.add(record)
            if (out.size >= limit) break
        }
        return out
    }

    fun deleteByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        synchronized(HISTORY_LOCK) {
            val list = readAllInternal()
            val before = list.size
            val remained = list.filterNot { ids.contains(it.id) }
            writeAllInternal(remained)
            return (before - remained.size).coerceAtLeast(0)
        }
    }

    fun clearAll() {
        synchronized(HISTORY_LOCK) { writeAllInternal(emptyList()) }
    }

    private fun AsrHistoryRecord.shouldIncludeInRecent(): Boolean {
        return text.isNotBlank() || isUnsuccessful
    }
}
