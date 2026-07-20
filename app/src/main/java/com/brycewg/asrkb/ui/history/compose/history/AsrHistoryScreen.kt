/**
 * ASR 识别历史 Compose 页面。
 *
 * 归属模块：ui/history/compose/history
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.history.compose.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsAssistChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFilterChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSearchField
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AsrHistoryScreen(
    uiMode: BibiUiMode,
    records: List<AsrHistoryStore.AsrHistoryRecord>,
    query: String,
    filterState: HistoryFilterState,
    selectedIds: Set<String>,
    displayLimit: Int,
    pageSize: Int,
    vendorOptions: List<HistoryVendorOption>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilterState) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onLoadMore: () -> Unit,
    onCopy: (String) -> Unit,
    audioRecordIds: Set<String>,
    llmAvailable: Boolean,
    onReRecognize: suspend (AsrHistoryStore.AsrHistoryRecord) -> AsrHistoryStore.AsrHistoryRecord,
    onReprocess: suspend (AsrHistoryStore.AsrHistoryRecord) -> AsrHistoryStore.AsrHistoryRecord,
    onRecordUpdated: (AsrHistoryStore.AsrHistoryRecord) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onOpenApiLog: () -> Unit,
    hasRecentApiErrors: Boolean,
    onHapticTap: () -> Unit
) {
    val filteredRecords = remember(records, query, filterState) {
        filterHistoryRecords(records, query, filterState)
    }
    val filteredIds = remember(filteredRecords) { filteredRecords.map { it.id }.toSet() }
    val selectedVisibleIds = remember(selectedIds, filteredIds) { selectedIds.intersect(filteredIds) }
    val isSearching = query.trim().isNotEmpty()
    val visibleRecords = remember(filteredRecords, displayLimit, isSearching) {
        if (isSearching) {
            filteredRecords
        } else {
            filteredRecords.take(displayLimit.coerceAtLeast(pageSize))
        }
    }
    val rows = remember(visibleRecords, selectedVisibleIds) {
        buildHistoryRows(visibleRecords, selectedVisibleIds)
    }
    val hasMore = !isSearching && visibleRecords.size < filteredRecords.size
    val listState = rememberLazyListState()
    var showFilterDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<AsrHistoryStore.AsrHistoryRecord?>(null) }
    var rerunJob by remember { mutableStateOf<Job?>(null) }
    var rerunError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(filteredIds, selectedIds) {
        if (selectedVisibleIds != selectedIds) {
            onSelectionChange(selectedVisibleIds)
        }
    }

    LaunchedEffect(listState, hasMore, rows.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (hasMore && lastVisible >= (rows.size - 4).coerceAtLeast(0)) {
                    onLoadMore()
                }
            }
    }

    HistoryScaffold(
        uiMode = uiMode,
        onBack = onBack,
        onOpenApiLog = {
            onHapticTap()
            onOpenApiLog()
        },
        hasRecentApiErrors = hasRecentApiErrors
    ) { innerPadding, scrollModifier ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = SettingsLayoutMetrics.PageHorizontalPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = dimensionResource(R.dimen.settings_form_max_width))
            ) {
                HistoryActionBar(
                    uiMode = uiMode,
                    selectedCount = selectedVisibleIds.size,
                    hasData = filteredRecords.isNotEmpty(),
                    onFilter = {
                        onHapticTap()
                        showFilterDialog = true
                    },
                    onSelectAll = {
                        onHapticTap()
                        onSelectAll(filteredIds)
                    },
                    onClearSelection = {
                        onHapticTap()
                        onClearSelection()
                    },
                    onDeleteSelected = {
                        onHapticTap()
                        showDeleteDialog = true
                    }
                )
                HistorySearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    uiMode = uiMode
                )
                if (rows.isEmpty()) {
                    EmptyHistoryState(uiMode = uiMode)
                } else {
                    HistoryList(
                        rows = rows,
                        uiMode = uiMode,
                        vendorOptions = vendorOptions,
                        selectedCount = selectedVisibleIds.size,
                        listState = listState,
                        scrollModifier = scrollModifier,
                        onToggleSelection = { id ->
                            onHapticTap()
                            onSelectionChange(toggleId(selectedVisibleIds, id))
                        },
                        onCopy = {
                            onHapticTap()
                            onCopy(it)
                        },
                        onOpenDetails = { selectedRecord = it }
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        HistoryFilterDialog(
            uiMode = uiMode,
            vendorOptions = vendorOptions,
            filterState = filterState,
            onDismiss = { showFilterDialog = false },
            onApply = {
                showFilterDialog = false
                onFilterChange(it)
            },
            onReset = {
                showFilterDialog = false
                onFilterChange(HistoryFilterState())
            }
        )
    }
    if (showDeleteDialog) {
        DeleteSelectedDialog(
            uiMode = uiMode,
            selectedCount = selectedVisibleIds.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeleteSelected(selectedVisibleIds)
            }
        )
    }
    selectedRecord?.let { record ->
        HistoryDetailsDialog(
            record = record,
            uiMode = uiMode,
            hasAudio = record.id in audioRecordIds,
            llmAvailable = llmAvailable,
            working = rerunJob?.isActive == true,
            error = rerunError,
            onDismissStarted = {
                rerunJob?.cancel()
                rerunJob = null
                rerunError = null
            },
            onDismiss = {
                selectedRecord = null
            },
            onCopy = { onCopy(it) },
            onReRecognize = {
                rerunError = null
                rerunJob = scope.launch {
                    runCatching { onReRecognize(record) }
                        .onSuccess {
                            selectedRecord = it
                            onRecordUpdated(it)
                        }
                        .onFailure { rerunError = it.message ?: "rerun_failed" }
                }
            },
            onReprocess = {
                rerunError = null
                rerunJob = scope.launch {
                    runCatching { onReprocess(record) }
                        .onSuccess {
                            selectedRecord = it
                            onRecordUpdated(it)
                        }
                        .onFailure { rerunError = it.message ?: "postprocess_failed" }
                }
            }
        )
    }
}

@Composable
private fun HistoryScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenApiLog: () -> Unit,
    hasRecentApiErrors: Boolean,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val apiLogLabel = stringResource(R.string.menu_api_log)
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_asr_history,
        onBack = onBack,
        actions = {
            when (uiMode) {
                BibiUiMode.Material -> IconButton(onClick = onOpenApiLog) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = apiLogLabel,
                        tint = if (hasRecentApiErrors) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                BibiUiMode.Miuix -> MiuixIconButton(onClick = onOpenApiLog) {
                    MiuixIcon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = apiLogLabel,
                        tint = if (hasRecentApiErrors) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        }
                    )
                }
            }
        },
        content = content
    )
}

@Composable
private fun HistoryActionBar(
    uiMode: BibiUiMode,
    selectedCount: Int,
    hasData: Boolean,
    onFilter: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(
            uiMode = uiMode,
            label = stringResource(R.string.menu_filter),
            icon = Icons.Rounded.FilterList,
            onClick = onFilter
        )
        if (selectedCount == 0 && hasData) {
            ActionChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_select_all),
                icon = Icons.Rounded.DoneAll,
                onClick = onSelectAll
            )
        }
        if (selectedCount > 0) {
            ActionChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_clear_selection),
                icon = Icons.Rounded.Clear,
                onClick = onClearSelection
            )
            ActionChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_delete_selected),
                icon = Icons.Rounded.Delete,
                onClick = onDeleteSelected
            )
            CountChip(count = selectedCount, uiMode = uiMode)
        }
    }
}

@Composable
private fun ActionChip(
    uiMode: BibiUiMode,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    SettingsAssistChip(
        uiMode = uiMode,
        label = label,
        icon = icon,
        onClick = onClick
    )
}

@Composable
private fun CountChip(count: Int, uiMode: BibiUiMode) {
    SettingsAssistChip(
        uiMode = uiMode,
        label = count.toString()
    )
}

@Composable
private fun HistorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    uiMode: BibiUiMode
) {
    SettingsSearchField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.hint_search_history),
        uiMode = uiMode
    )
}

@Composable
private fun HistoryList(
    rows: List<HistoryRow>,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    selectedCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollModifier: Modifier,
    onToggleSelection: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenDetails: (AsrHistoryStore.AsrHistoryRecord) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(scrollModifier),
        contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is HistoryRow.Header -> "header-${row.section.name}"
                    is HistoryRow.Item -> row.record.id
                }
            }
        ) { row ->
            when (row) {
                is HistoryRow.Header -> SectionHeader(section = row.section, uiMode = uiMode)
                is HistoryRow.Item -> HistoryItemCard(
                    row = row,
                    uiMode = uiMode,
                    vendorOptions = vendorOptions,
                    selectedCount = selectedCount,
                    onToggleSelection = onToggleSelection,
                    onCopy = onCopy,
                    onOpenDetails = onOpenDetails
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: HistorySection, uiMode: BibiUiMode) {
    val title = when (section) {
        HistorySection.WITHIN_2H -> stringResource(R.string.history_section_2h)
        HistorySection.TODAY -> stringResource(R.string.history_section_today)
        HistorySection.LAST_7D -> stringResource(R.string.history_section_7d)
        HistorySection.LAST_30D -> stringResource(R.string.history_section_30d)
        HistorySection.OLDER -> stringResource(R.string.history_section_older)
    }
    HistoryText(
        text = title,
        uiMode = uiMode,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        header = true
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistoryItemCard(
    row: HistoryRow.Item,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    selectedCount: Int,
    onToggleSelection: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenDetails: (AsrHistoryStore.AsrHistoryRecord) -> Unit
) {
    val record = row.record
    val onClick = {
        if (selectedCount > 0) onToggleSelection(record.id) else onOpenDetails(record)
    }
    val onLongClick = { onToggleSelection(record.id) }
    when (uiMode) {
        BibiUiMode.Material -> {
            // clickable 必须落在 clip(shape) 之内，否则按压遮罩会画出直角方框。
            val shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape)
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
                shape = shape,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (row.selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                )
            ) {
                HistoryItemContent(record, uiMode, vendorOptions, onCopy)
            }
        }

        BibiUiMode.Miuix -> {
            // 使用 Card 自带 onClick/onLongPress，指示器画在圆角 clip 内，避免外层再叠一层遮罩。
            val cornerRadius = MiuixCardDefaults.CornerRadius
            MiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (row.selected) {
                            Modifier.border(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.primary,
                                shape = RoundedCornerShape(cornerRadius)
                            )
                        } else {
                            Modifier
                        }
                    ),
                cornerRadius = cornerRadius,
                onClick = onClick,
                onLongPress = onLongClick,
                showIndication = true
            ) {
                HistoryItemContent(record, uiMode, vendorOptions, onCopy)
            }
        }
    }
}

@Composable
private fun HistoryItemContent(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    onCopy: (String) -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timestamp = remember(record.timestamp) { formatter.format(Date(record.timestamp)) }
    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                HistoryText(text = timestamp, uiMode = uiMode, compact = true)
                HistoryText(
                    text = record.text,
                    uiMode = uiMode,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 4
                )
            }
            SettingsAssistChip(
                uiMode = uiMode,
                label = stringResource(R.string.btn_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = { onCopy(record.text) }
            )
        }
        HistoryText(
            text = buildMeta(record, vendorOptions),
            uiMode = uiMode,
            compact = true,
            secondary = true,
            maxLines = 3
        )
    }
}

@Composable
private fun buildMeta(
    record: AsrHistoryStore.AsrHistoryRecord,
    vendorOptions: List<HistoryVendorOption>
): String {
    val vendor = vendorOptions.firstOrNull { it.id == record.vendorId }?.label ?: record.vendorId
    val source = when (record.source) {
        "floating" -> stringResource(R.string.source_floating_full)
        "external" -> stringResource(R.string.source_external_full)
        "ime" -> stringResource(R.string.source_ime_full)
        else -> record.source
    }
    val aiStatus = when (record.aiPostStatus) {
        AsrHistoryStore.AiPostStatus.SUCCESS -> stringResource(R.string.ai_processed_yes)
        AsrHistoryStore.AiPostStatus.FAILED -> stringResource(R.string.ai_processed_failed)
        AsrHistoryStore.AiPostStatus.NONE -> if (record.aiProcessed) {
            stringResource(R.string.ai_processed_yes)
        } else {
            stringResource(R.string.ai_processed_no)
        }
    }
    val parts = mutableListOf(
        vendor,
        source,
        aiStatus,
        "${record.charCount}${stringResource(R.string.unit_chars)}"
    )
    if (record.totalElapsedMs > 0) {
        parts.add(stringResource(R.string.meta_total_elapsed_seconds, record.totalElapsedMs / 1000.0))
    }
    parts.add(stringResource(R.string.meta_total_seconds, record.audioMs / 1000.0))
    if (record.procMs > 0) {
        parts.add(stringResource(R.string.meta_proc_seconds, record.procMs / 1000.0))
    }
    if (record.aiPostStatus != AsrHistoryStore.AiPostStatus.NONE || record.aiPostMs > 0) {
        parts.add(stringResource(R.string.meta_ai_postproc_seconds, record.aiPostMs / 1000.0))
    }
    return parts.joinToString("·")
}

@Composable
private fun HistoryDetailsDialog(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode,
    hasAudio: Boolean,
    llmAvailable: Boolean,
    working: Boolean,
    error: String?,
    onDismissStarted: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onReRecognize: () -> Unit,
    onReprocess: () -> Unit
) {
    val exit = rememberSettingsDialogExitController(record.id)
    fun finishDismiss() {
        exit.finish()
    }
    fun dismissDialog() {
        if (!exit.show) return
        onDismissStarted()
        exit.dismiss(onDismiss)
    }

    val content: @Composable () -> Unit = {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HistoryResultSection(
                    title = stringResource(R.string.history_raw_text),
                    value = record.rawText ?: stringResource(R.string.history_raw_unavailable),
                    canCopy = !record.rawText.isNullOrBlank(),
                    uiMode = uiMode,
                    onCopy = { record.rawText?.let(onCopy) }
                )
                HistoryResultSection(
                    title = stringResource(R.string.history_final_text),
                    value = record.text,
                    canCopy = record.text.isNotBlank(),
                    uiMode = uiMode,
                    onCopy = { onCopy(record.text) }
                )
                if (!hasAudio) {
                    HistoryText(
                        text = stringResource(R.string.history_audio_unavailable),
                        uiMode = uiMode,
                        compact = true,
                        secondary = true
                    )
                }
                if (!llmAvailable) {
                    HistoryText(
                        text = stringResource(R.string.history_llm_unavailable),
                        uiMode = uiMode,
                        compact = true,
                        secondary = true
                    )
                }
                if (working) {
                    HistoryText(
                        text = stringResource(R.string.history_rerun_working),
                        uiMode = uiMode,
                        secondary = true
                    )
                }
                error?.let {
                    val errorMessage = when (it) {
                        "audio_unavailable" -> stringResource(R.string.history_audio_unavailable)
                        "llm_unavailable" -> stringResource(R.string.history_llm_unavailable)
                        "engine_unavailable", "engine_pcm_unsupported" ->
                            stringResource(R.string.history_rerun_engine_unavailable)
                        "record_missing" -> stringResource(R.string.history_record_missing)
                        else -> it
                    }
                    HistoryText(
                        text = stringResource(R.string.history_rerun_error, errorMessage),
                        uiMode = uiMode,
                        secondary = true
                    )
                }
            }
        }
    }
    val actions = listOf(
        SettingsDialogAction(
            text = stringResource(R.string.btn_rerecognize),
            onClick = onReRecognize,
            enabled = hasAudio && !working
        ),
        SettingsDialogAction(
            text = stringResource(R.string.btn_reprocess),
            onClick = onReprocess,
            enabled = llmAvailable && !working,
            primary = true
        )
    )
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "HistoryDetailsDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                title = stringResource(R.string.history_details_title),
                onDismissRequest = ::dismissDialog,
                modifier = Modifier.graphicsLayer(alpha = alpha),
                text = content,
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions.map {
                            MaterialSettingsDialogAction(
                                text = it.text,
                                onClick = it.onClick,
                                enabled = it.enabled,
                                primary = it.primary
                            )
                        }
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = stringResource(R.string.history_details_title),
            onDismissRequest = ::dismissDialog,
            onDismissFinished = ::finishDismiss
        ) {
            content()
            Spacer(modifier = Modifier.height(12.dp))
            SettingsDialogActionRow(uiMode = uiMode, actions = actions)
        }
    }
}

@Composable
private fun HistoryResultSection(
    title: String,
    value: String,
    canCopy: Boolean,
    uiMode: BibiUiMode,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryText(text = title, uiMode = uiMode, header = true)
            SettingsAssistChip(
                uiMode = uiMode,
                label = stringResource(R.string.btn_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = onCopy.takeIf { canCopy }
            )
        }
        HistoryText(text = value, uiMode = uiMode)
    }
}

@Composable
private fun EmptyHistoryState(uiMode: BibiUiMode) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        HistoryText(
            text = stringResource(R.string.empty_history),
            uiMode = uiMode,
            secondary = true
        )
    }
}

@Composable
private fun HistoryText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    compact: Boolean = false,
    secondary: Boolean = false,
    maxLines: Int = 2
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = modifier,
            style = when {
                header -> MaterialTheme.typography.titleSmall
                compact -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = if (secondary) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            style = when {
                header -> MiuixTheme.textStyles.body2
                compact -> MiuixTheme.textStyles.footnote1
                else -> MiuixTheme.textStyles.body1
            },
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = if (secondary) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeleteSelectedDialog(
    uiMode: BibiUiMode,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.dialog_delete_selected_title)
    val message = stringResource(R.string.dialog_delete_selected_msg, selectedCount)
    val confirm = stringResource(R.string.dialog_filter_ok)
    val cancel = stringResource(R.string.dialog_filter_cancel)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "DeleteSelectedDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = { Text(message) },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(cancel, onClick = { exit.dismiss(onDismiss) }),
                            MaterialSettingsDialogAction(confirm, onClick = { exit.dismiss(onConfirm) })
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            summary = message,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = cancel,
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = confirm,
                        onClick = { exit.dismiss(onConfirm) },
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun HistoryFilterDialog(
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    filterState: HistoryFilterState,
    onDismiss: () -> Unit,
    onApply: (HistoryFilterState) -> Unit,
    onReset: () -> Unit
) {
    var tempVendorIds by remember(filterState) {
        mutableStateOf(filterState.vendorIds)
    }
    var tempSources by remember(filterState) {
        mutableStateOf(filterState.sources)
    }
    var tempTimeFilter by remember(filterState) {
        mutableStateOf(filterState.timeFilter)
    }
    val title = stringResource(R.string.dialog_filter_title)
    val confirm = stringResource(R.string.dialog_filter_ok)
    val cancel = stringResource(R.string.dialog_filter_cancel)
    val reset = stringResource(R.string.dialog_filter_reset)
    val exit = rememberSettingsDialogExitController(filterState)
    fun finishDismiss() {
        exit.finish()
    }
    fun applyFilter() {
        exit.dismiss {
            onApply(
                HistoryFilterState(
                    vendorIds = tempVendorIds,
                    sources = tempSources,
                    timeFilter = tempTimeFilter
                )
            )
        }
    }

    val content: @Composable () -> Unit = {
        FilterDialogContent(
            uiMode = uiMode,
            vendorOptions = vendorOptions,
            vendorIds = tempVendorIds,
            sources = tempSources,
            timeFilter = tempTimeFilter,
            onVendorIdsChange = { tempVendorIds = it },
            onSourcesChange = { tempSources = it },
            onTimeFilterChange = { tempTimeFilter = it }
        )
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "HistoryFilterDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = content,
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(reset, onClick = { exit.dismiss(onReset) }),
                            MaterialSettingsDialogAction(cancel, onClick = { exit.dismiss(onDismiss) }),
                            MaterialSettingsDialogAction(
                                text = confirm,
                                onClick = ::applyFilter
                            )
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            content()
            Spacer(modifier = Modifier.height(12.dp))
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = reset,
                        onClick = { exit.dismiss(onReset) }
                    ),
                    SettingsDialogAction(
                        text = cancel,
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = confirm,
                        onClick = ::applyFilter,
                        primary = true
                    )
                ),
                spacing = 10.dp
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterDialogContent(
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    vendorIds: Set<String>,
    sources: Set<String>,
    timeFilter: TimeFilter,
    onVendorIdsChange: (Set<String>) -> Unit,
    onSourcesChange: (Set<String>) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit
) {
    val sourceOptions = listOf(
        "ime" to stringResource(R.string.source_ime),
        "floating" to stringResource(R.string.source_floating),
        "external" to stringResource(R.string.source_external)
    )
    val timeOptions = listOf(
        TimeFilter.ALL to stringResource(R.string.filter_all),
        TimeFilter.WITHIN_2H to stringResource(R.string.history_section_2h),
        TimeFilter.TODAY to stringResource(R.string.history_section_today),
        TimeFilter.LAST_7D to stringResource(R.string.history_section_7d),
        TimeFilter.LAST_30D to stringResource(R.string.history_section_30d)
    )

    Column(
        modifier = Modifier
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilterGroupTitle(text = stringResource(R.string.label_vendor), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterOptionChip(
                label = stringResource(R.string.filter_all),
                selected = vendorIds.isEmpty(),
                uiMode = uiMode,
                onClick = { onVendorIdsChange(emptySet()) }
            )
            vendorOptions.forEach { vendor ->
                FilterOptionChip(
                    label = vendor.label,
                    selected = vendor.id in vendorIds,
                    uiMode = uiMode,
                    onClick = {
                        val next = if (vendor.id in vendorIds) {
                            vendorIds - vendor.id
                        } else {
                            vendorIds + vendor.id
                        }
                        onVendorIdsChange(next)
                    }
                )
            }
        }

        FilterGroupTitle(text = stringResource(R.string.label_source), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterOptionChip(
                label = stringResource(R.string.filter_all),
                selected = sources.isEmpty(),
                uiMode = uiMode,
                onClick = { onSourcesChange(emptySet()) }
            )
            sourceOptions.forEach { (id, label) ->
                FilterOptionChip(
                    label = label,
                    selected = sources.firstOrNull() == id,
                    uiMode = uiMode,
                    onClick = { onSourcesChange(setOf(id)) }
                )
            }
        }

        FilterGroupTitle(text = stringResource(R.string.label_time), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            timeOptions.forEach { (filter, label) ->
                FilterOptionChip(
                    label = label,
                    selected = timeFilter == filter,
                    uiMode = uiMode,
                    onClick = { onTimeFilterChange(filter) }
                )
            }
        }
    }
}

@Composable
private fun FilterGroupTitle(text: String, uiMode: BibiUiMode) {
    HistoryText(
        text = text,
        uiMode = uiMode,
        header = true,
        maxLines = 1
    )
}

@Composable
private fun FilterOptionChip(
    label: String,
    selected: Boolean,
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    SettingsFilterChip(
        uiMode = uiMode,
        label = label,
        selected = selected,
        onClick = onClick
    )
}

private fun toggleId(ids: Set<String>, id: String): Set<String> = if (id in ids) ids - id else ids + id
