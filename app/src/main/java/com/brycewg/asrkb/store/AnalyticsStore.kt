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
 * 统计事件本地缓存。
 *
 * - 与历史记录类似，使用 SharedPreferences(JSON) 存储
 * - 仅保存必要字段，不包含识别文本内容
 */
class AnalyticsStore(context: Context) {
    companion object {
        private const val TAG = "AnalyticsStore"
        private const val SP_NAME = "asr_prefs"
        private const val KEY_ASR_EVENTS_JSON = "analytics_asr_events"
        private const val KEY_APP_STARTS_JSON = "analytics_app_starts"
    }

    private val sp: SharedPreferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Serializable
    data class AsrEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long,
        val vendorId: String,
        val audioMs: Long,
        val procMs: Long,
        // "ime" | "floating"
        val source: String,
        val aiProcessed: Boolean,
        val charCount: Int,
    )

    @Serializable
    data class AppStartEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long,
    )

    private fun readAsrEventsInternal(): MutableList<AsrEvent> {
        val raw = sp.getString(KEY_ASR_EVENTS_JSON, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<AsrEvent>>(raw).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ASR events JSON", e)
            mutableListOf()
        }
    }

    private fun writeAsrEventsInternal(list: List<AsrEvent>) {
        try {
            val text = json.encodeToString(list)
            sp.edit().putString(KEY_ASR_EVENTS_JSON, text).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ASR events JSON", e)
        }
    }

    private fun readAppStartsInternal(): MutableList<AppStartEvent> {
        val raw = sp.getString(KEY_APP_STARTS_JSON, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<AppStartEvent>>(raw).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse app starts JSON", e)
            mutableListOf()
        }
    }

    private fun writeAppStartsInternal(list: List<AppStartEvent>) {
        try {
            val text = json.encodeToString(list)
            sp.edit().putString(KEY_APP_STARTS_JSON, text).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write app starts JSON", e)
        }
    }

    fun addAsrEvent(event: AsrEvent) {
        val list = readAsrEventsInternal()
        list.add(event)
        writeAsrEventsInternal(list)
    }

    fun addAppStart(event: AppStartEvent) {
        val list = readAppStartsInternal()
        list.add(event)
        writeAppStartsInternal(list)
    }

    fun listAsrEvents(): List<AsrEvent> =
        readAsrEventsInternal().sortedBy { it.timestamp }

    fun listAppStarts(): List<AppStartEvent> =
        readAppStartsInternal().sortedBy { it.timestamp }

    fun deleteAsrEventsByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val list = readAsrEventsInternal()
        val before = list.size
        val remained = list.filterNot { ids.contains(it.id) }
        writeAsrEventsInternal(remained)
        return (before - remained.size).coerceAtLeast(0)
    }

    fun deleteAppStartsByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val list = readAppStartsInternal()
        val before = list.size
        val remained = list.filterNot { ids.contains(it.id) }
        writeAppStartsInternal(remained)
        return (before - remained.size).coerceAtLeast(0)
    }
}
