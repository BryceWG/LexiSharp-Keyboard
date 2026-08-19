/**
 * ASR 历史 Compose 页状态与列表分组模型。
 *
 * 归属模块：ui/history/compose/history
 */
package com.brycewg.asrkb.ui.history.compose.history

import com.brycewg.asrkb.store.AsrHistoryStore
import java.util.Calendar

enum class TimeFilter {
    ALL,
    WITHIN_2H,
    TODAY,
    LAST_7D,
    LAST_30D
}

data class HistoryFilterState(
    val vendorIds: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val timeFilter: TimeFilter = TimeFilter.ALL
)

data class HistoryVendorOption(
    val id: String,
    val label: String
)

enum class HistorySection {
    WITHIN_2H,
    TODAY,
    LAST_7D,
    LAST_30D,
    OLDER
}

sealed interface HistoryRow {
    data class Header(val section: HistorySection) : HistoryRow
    data class Item(
        val record: AsrHistoryStore.AsrHistoryRecord,
        val selected: Boolean
    ) : HistoryRow
}

fun filterHistoryRecords(
    records: List<AsrHistoryStore.AsrHistoryRecord>,
    query: String,
    filterState: HistoryFilterState,
    now: Long = System.currentTimeMillis(),
    failDisplayText: (AsrHistoryStore.AsrHistoryRecord) -> String = { "" }
): List<AsrHistoryStore.AsrHistoryRecord> {
    val trimmedQuery = query.trim()
    val startOfToday = startOfToday(now)
    val twoHoursMs = 2 * 60 * 60 * 1000L
    val weekMs = 7 * 24 * 60 * 60 * 1000L
    val monthMs = 30 * 24 * 60 * 60 * 1000L

    return records.filter { record ->
        val okVendor = filterState.vendorIds.isEmpty() || record.vendorId in filterState.vendorIds
        val okSource = filterState.sources.isEmpty() || record.source in filterState.sources
        val okText = trimmedQuery.isEmpty() ||
            record.text.contains(trimmedQuery, ignoreCase = true) ||
            record.rawText?.contains(trimmedQuery, ignoreCase = true) == true ||
            failDisplayText(record).contains(trimmedQuery, ignoreCase = true)
        val okTime = when (filterState.timeFilter) {
            TimeFilter.ALL -> true
            TimeFilter.WITHIN_2H -> record.timestamp >= now - twoHoursMs
            TimeFilter.TODAY -> record.timestamp in startOfToday..now
            TimeFilter.LAST_7D -> record.timestamp >= now - weekMs
            TimeFilter.LAST_30D -> record.timestamp >= now - monthMs
        }
        okVendor && okSource && okText && okTime
    }
}

fun buildHistoryRows(
    records: List<AsrHistoryStore.AsrHistoryRecord>,
    selectedIds: Set<String>,
    now: Long = System.currentTimeMillis()
): List<HistoryRow> {
    val startOfToday = startOfToday(now)
    val twoHoursMs = 2 * 60 * 60 * 1000L
    val weekMs = 7 * 24 * 60 * 60 * 1000L
    val monthMs = 30 * 24 * 60 * 60 * 1000L
    val rows = mutableListOf<HistoryRow>()

    fun addSection(section: HistorySection, items: List<AsrHistoryStore.AsrHistoryRecord>) {
        if (items.isEmpty()) return
        rows.add(HistoryRow.Header(section))
        items.forEach { record ->
            rows.add(HistoryRow.Item(record, selected = record.id in selectedIds))
        }
    }

    addSection(HistorySection.WITHIN_2H, records.filter { it.timestamp >= now - twoHoursMs })
    addSection(
        HistorySection.TODAY,
        records.filter { it.timestamp in startOfToday..(now - twoHoursMs) }
    )
    addSection(
        HistorySection.LAST_7D,
        records.filter { it.timestamp in (now - weekMs)..(startOfToday - 1) }
    )
    addSection(
        HistorySection.LAST_30D,
        records.filter { it.timestamp in (now - monthMs)..(now - weekMs - 1) }
    )
    addSection(HistorySection.OLDER, records.filter { it.timestamp < now - monthMs })
    return rows
}

private fun startOfToday(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
