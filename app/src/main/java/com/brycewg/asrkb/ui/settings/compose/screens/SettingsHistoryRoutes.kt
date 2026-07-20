/**
 * 设置宿主内的历史记录与 API Log 路由。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.history.AsrHistoryRerunCoordinator
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.history.compose.apilog.ApiLogScreen
import com.brycewg.asrkb.ui.history.compose.apilog.formatApiLogDetail
import com.brycewg.asrkb.ui.history.compose.history.AsrHistoryScreen
import com.brycewg.asrkb.ui.history.compose.history.HistoryFilterState
import com.brycewg.asrkb.ui.history.compose.history.HistoryVendorOption
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsHistoryRoutes"
private const val HISTORY_PAGE_SIZE = 30

@Composable
internal fun SettingsHistoryRoute(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenApiLog: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { AsrHistoryStore(appContext) }
    val audioStore = remember(appContext) { AsrHistoryAudioStore(appContext) }
    val hapticTap = LocalSettingsHapticTap.current
    val scope = rememberCoroutineScope()
    val rerunCoordinator = remember(appContext, scope) {
        AsrHistoryRerunCoordinator(appContext, scope)
    }
    val prefs = remember(appContext) { Prefs(appContext) }
    val vendors = remember(context) {
        AsrVendorUi.ordered().map { vendor ->
            HistoryVendorOption(vendor.id, AsrVendorUi.name(context, vendor))
        }
    }
    var records by remember { mutableStateOf<List<AsrHistoryStore.AsrHistoryRecord>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filterState by remember { mutableStateOf(HistoryFilterState()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var displayLimit by remember { mutableIntStateOf(HISTORY_PAGE_SIZE) }
    var hasRecentApiErrors by remember { mutableStateOf(false) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var audioRecordIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun showHistoryMessage(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_asr_history),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    suspend fun loadData(resetPage: Boolean) {
        records = withContext(Dispatchers.IO) { store.listAll() }
        audioRecordIds = withContext(Dispatchers.IO) {
            records.asSequence().filter { audioStore.hasAudio(it.id) }.map { it.id }.toSet()
        }
        hasRecentApiErrors = withContext(Dispatchers.IO) {
            ApiLogStore.listAll().take(10).any { !it.success && !it.canceled }
        }
        if (resetPage) displayLimit = HISTORY_PAGE_SIZE
    }

    LaunchedEffect(store) {
        loadData(resetPage = true)
    }

    SettingsMessageDialog(
        state = messageDialog,
        uiMode = uiMode,
        onDismiss = { messageDialog = null }
    )

    AsrHistoryScreen(
        uiMode = uiMode,
        records = records,
        query = query,
        filterState = filterState,
        selectedIds = selectedIds,
        displayLimit = displayLimit,
        pageSize = HISTORY_PAGE_SIZE,
        vendorOptions = vendors,
        onBack = onBack,
        onQueryChange = { value ->
            val wasEmpty = query.trim().isEmpty()
            val isEmpty = value.trim().isEmpty()
            query = value
            if (!wasEmpty && isEmpty) displayLimit = HISTORY_PAGE_SIZE
        },
        onFilterChange = { state ->
            filterState = state
            displayLimit = HISTORY_PAGE_SIZE
        },
        onSelectionChange = { selectedIds = it },
        onSelectAll = { selectedIds = it },
        onClearSelection = { selectedIds = emptySet() },
        onLoadMore = { displayLimit += HISTORY_PAGE_SIZE },
        onCopy = { text ->
            if (copyToClipboard(context, label = "ASR", text = text)) {
                showHistoryMessage(context.getString(R.string.toast_copied))
            }
        },
        audioRecordIds = audioRecordIds,
        llmAvailable = prefs.hasLlmKeys(),
        onReRecognize = rerunCoordinator::reRecognize,
        onReprocess = rerunCoordinator::reprocess,
        onRecordUpdated = { updated ->
            records = records.map { if (it.id == updated.id) updated else it }
        },
        onDeleteSelected = { ids ->
            if (ids.isNotEmpty()) {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { store.deleteByIds(ids) }
                    withContext(Dispatchers.IO) { ids.forEach(audioStore::delete) }
                    showHistoryMessage(context.getString(R.string.toast_deleted, deleted))
                    selectedIds = emptySet()
                    loadData(resetPage = true)
                }
            }
        },
        onOpenApiLog = onOpenApiLog,
        hasRecentApiErrors = hasRecentApiErrors,
        onHapticTap = hapticTap
    )
}

@Composable
internal fun SettingsApiLogRoute(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ApiLogStore.ApiLogRecord>>(emptyList()) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    fun showApiLogMessage(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_api_log),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    suspend fun loadData() {
        records = withContext(Dispatchers.IO) { ApiLogStore.listAll() }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    SettingsMessageDialog(
        state = messageDialog,
        uiMode = uiMode,
        onDismiss = { messageDialog = null }
    )

    ApiLogScreen(
        records = records,
        uiMode = uiMode,
        onBack = onBack,
        onClearConfirmed = {
            scope.launch {
                withContext(Dispatchers.IO) { ApiLogStore.clearAll() }
                showApiLogMessage(context.getString(R.string.toast_api_log_cleared))
                loadData()
            }
        },
        onCopyDetails = { record ->
            if (copyToClipboard(context, label = "api_log", text = formatApiLogDetail(context, record))) {
                showApiLogMessage(context.getString(R.string.toast_copied))
            }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String): Boolean = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    true
} catch (e: Exception) {
    Log.e(TAG, "copyToClipboard failed", e)
    false
}
