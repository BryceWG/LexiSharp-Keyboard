/**
 * API Log Compose 页面：搜索、筛选、列表、清空确认与详情弹窗。
 *
 * 归属模块：ui/history/compose/apilog
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.history.compose.apilog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFilterChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSearchField
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ApiLogFilter(val labelRes: Int) {
    All(R.string.filter_all),
    Asr(R.string.api_log_filter_asr),
    Llm(R.string.api_log_filter_llm),
    Failed(R.string.api_log_filter_failed)
}

@Composable
fun ApiLogScreen(
    records: List<ApiLogStore.ApiLogRecord>,
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onClearConfirmed: () -> Unit,
    onCopyDetails: (ApiLogStore.ApiLogRecord) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ApiLogFilter.All) }
    var clearDialogVisible by rememberSaveable { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<ApiLogStore.ApiLogRecord?>(null) }

    val filteredRecords = remember(records, query, activeFilter) {
        val trimmedQuery = query.trim()
        records.filter { record ->
            val categoryMatches = when (activeFilter) {
                ApiLogFilter.All -> true
                ApiLogFilter.Asr -> record.category.equals("ASR", ignoreCase = true)
                ApiLogFilter.Llm -> record.category.equals("LLM", ignoreCase = true)
                ApiLogFilter.Failed -> !record.success && !record.canceled
            }
            val queryMatches = trimmedQuery.isEmpty() ||
                apiLogSearchableText(record).contains(trimmedQuery, ignoreCase = true)
            categoryMatches && queryMatches
        }
    }

    ApiLogScaffold(
        uiMode = uiMode,
        onBack = onBack,
        onClear = { clearDialogVisible = true }
    ) { innerPadding, scrollModifier ->
        ApiLogContent(
            records = records,
            filteredRecords = filteredRecords,
            query = query,
            activeFilter = activeFilter,
            uiMode = uiMode,
            onQueryChange = { query = it },
            onFilterChange = { activeFilter = it },
            onRecordClick = { selectedRecord = it },
            scrollModifier = scrollModifier,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        )
    }

    if (clearDialogVisible) {
        ClearApiLogDialog(
            uiMode = uiMode,
            onDismiss = { clearDialogVisible = false },
            onConfirm = {
                clearDialogVisible = false
                onClearConfirmed()
            }
        )
    }

    selectedRecord?.let { record ->
        ApiLogDetailsDialog(
            record = record,
            uiMode = uiMode,
            onDismiss = { selectedRecord = null },
            onCopy = {
                onCopyDetails(record)
                selectedRecord = null
            }
        )
    }
}

@Composable
private fun ApiLogScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onClear: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val clearLabel = stringResource(R.string.menu_clear_api_log)
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_api_log,
        onBack = onBack,
        actions = {
            when (uiMode) {
                BibiUiMode.Material -> IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = clearLabel)
                }

                BibiUiMode.Miuix -> MiuixIconButton(onClick = onClear) {
                    MiuixIcon(Icons.Rounded.DeleteSweep, contentDescription = clearLabel)
                }
            }
        },
        content = content
    )
}

@Composable
private fun ApiLogContent(
    records: List<ApiLogStore.ApiLogRecord>,
    filteredRecords: List<ApiLogStore.ApiLogRecord>,
    query: String,
    activeFilter: ApiLogFilter,
    uiMode: BibiUiMode,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ApiLogFilter) -> Unit,
    onRecordClick: (ApiLogStore.ApiLogRecord) -> Unit,
    scrollModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = SettingsLayoutMetrics.PageHorizontalPadding)
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dimensionResource(R.dimen.settings_form_max_width))
                .fillMaxSize()
        ) {
            ApiLogFilters(
                activeFilter = activeFilter,
                uiMode = uiMode,
                onFilterChange = onFilterChange
            )
            ApiLogSearchField(
                query = query,
                uiMode = uiMode,
                onQueryChange = onQueryChange
            )
            if (filteredRecords.isEmpty()) {
                EmptyApiLogState(
                    message = stringResource(
                        if (records.isEmpty()) R.string.empty_api_log else R.string.empty_api_log_filtered
                    ),
                    uiMode = uiMode
                )
            } else {
                ApiLogList(
                    records = filteredRecords,
                    uiMode = uiMode,
                    scrollModifier = scrollModifier,
                    onRecordClick = onRecordClick
                )
            }
        }
    }
}

@Composable
private fun ApiLogFilters(
    activeFilter: ApiLogFilter,
    uiMode: BibiUiMode,
    onFilterChange: (ApiLogFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ApiLogFilter.entries.forEach { filter ->
            SettingsFilterChip(
                uiMode = uiMode,
                label = stringResource(filter.labelRes),
                selected = activeFilter == filter,
                onClick = { onFilterChange(filter) }
            )
        }
    }
}

@Composable
private fun ApiLogSearchField(
    query: String,
    uiMode: BibiUiMode,
    onQueryChange: (String) -> Unit
) {
    SettingsSearchField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.hint_search_api_log),
        uiMode = uiMode,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun ApiLogList(
    records: List<ApiLogStore.ApiLogRecord>,
    uiMode: BibiUiMode,
    scrollModifier: Modifier,
    onRecordClick: (ApiLogStore.ApiLogRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(scrollModifier),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = records,
            key = { it.id }
        ) { record ->
            ApiLogCard(
                record = record,
                uiMode = uiMode,
                onClick = { onRecordClick(record) }
            )
        }
    }
}

@Composable
private fun ApiLogCard(
    record: ApiLogStore.ApiLogRecord,
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    when (uiMode) {
        // 使用 Card 自带 onClick，按压指示器落在圆角 clip 内，避免外层 clickable 画出直角遮罩。
        BibiUiMode.Material -> ElevatedCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            ApiLogCardContent(record = record, uiMode = uiMode)
        }

        BibiUiMode.Miuix -> MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            showIndication = true
        ) {
            ApiLogCardContent(record = record, uiMode = uiMode)
        }
    }
}

@Composable
private fun ApiLogCardContent(
    record: ApiLogStore.ApiLogRecord,
    uiMode: BibiUiMode
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(statusColor(record, uiMode), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ApiLogText(
                text = apiLogTitle(context, record),
                uiMode = uiMode,
                strong = true,
                maxLines = 2
            )
            ApiLogText(
                text = apiLogTime(context, record),
                uiMode = uiMode,
                secondary = true,
                maxLines = 1
            )
            ApiLogText(
                text = formatApiLogEndpoint(context, record),
                uiMode = uiMode,
                maxLines = 2
            )
            ApiLogText(
                text = apiLogMeta(context, record),
                uiMode = uiMode,
                secondary = true,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun statusColor(record: ApiLogStore.ApiLogRecord, uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> when {
        record.canceled -> MaterialTheme.colorScheme.outline
        record.success -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    BibiUiMode.Miuix -> when {
        record.canceled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        record.success -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.error
    }
}

@Composable
private fun ApiLogText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    strong: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = modifier,
            style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (strong) FontWeight.SemiBold else null,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            style = if (secondary) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.body2,
            color = if (secondary) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
            fontWeight = if (strong) FontWeight.SemiBold else null,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyApiLogState(
    message: String,
    uiMode: BibiUiMode
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        ApiLogText(
            text = message,
            uiMode = uiMode,
            secondary = true,
            maxLines = 2
        )
    }
}

@Composable
private fun ClearApiLogDialog(
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.dialog_clear_api_log_title)
    val message = stringResource(R.string.dialog_clear_api_log_message)
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
                label = "ClearApiLogDialogAlpha"
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
                            MaterialSettingsDialogAction(
                                text = cancel,
                                onClick = { exit.dismiss(onDismiss) }
                            ),
                            MaterialSettingsDialogAction(
                                text = confirm,
                                onClick = { exit.dismiss(onConfirm) }
                            )
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
private fun ApiLogDetailsDialog(
    record: ApiLogStore.ApiLogRecord,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialDetailsDialog(record, onDismiss, onCopy)
        BibiUiMode.Miuix -> MiuixDetailsDialog(record, onDismiss, onCopy)
    }
}

@Composable
private fun MaterialDetailsDialog(
    record: ApiLogStore.ApiLogRecord,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val exit = rememberSettingsDialogExitController(record.id)
    fun finishDismiss() {
        exit.finish()
    }
    val alpha = animateSettingsDialogExitAlpha(
        show = exit.show,
        label = "ApiLogDetailsDialogAlpha"
    )
    MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
    MaterialSettingsAlertDialog(
        onDismissRequest = { exit.dismiss(onDismiss) },
        modifier = Modifier.graphicsLayer(alpha = alpha),
        titleContent = {
            Text(
                text = apiLogTitle(context, record),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            DetailsContent(
                record = record,
                uiMode = BibiUiMode.Material,
                modifier = Modifier.heightIn(max = 440.dp)
            )
        },
        buttons = {
            MaterialSettingsDialogButtonRow(
                actions = listOf(
                    MaterialSettingsDialogAction(
                        text = stringResource(R.string.btn_close),
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    MaterialSettingsDialogAction(
                        text = stringResource(R.string.btn_copy),
                        onClick = { exit.dismiss(onCopy) }
                    )
                )
            )
        }
    )
}

@Composable
private fun MiuixDetailsDialog(
    record: ApiLogStore.ApiLogRecord,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val exit = rememberSettingsDialogExitController(record.id)
    fun finishDismiss() {
        exit.finish()
    }

    OverlayDialog(
        show = exit.show,
        title = apiLogTitle(context, record),
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        DetailsContent(
            record = record,
            uiMode = BibiUiMode.Miuix,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .padding(bottom = 16.dp)
        )
        SettingsDialogActionRow(
            uiMode = BibiUiMode.Miuix,
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.btn_close),
                    onClick = { exit.dismiss(onDismiss) }
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.btn_copy),
                    onClick = { exit.dismiss(onCopy) },
                    primary = true
                )
            )
        )
    }
}
@Composable
private fun DetailsContent(
    record: ApiLogStore.ApiLogRecord,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SelectionContainer {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DetailsHeader(
                endpoint = formatApiLogEndpoint(context, record),
                meta = apiLogMeta(context, record),
                uiMode = uiMode
            )
            DetailSection(
                title = stringResource(R.string.api_log_request),
                value = record.requestSummary.ifBlank { "-" },
                uiMode = uiMode
            )
            DetailSection(
                title = stringResource(R.string.api_log_request_structure),
                value = record.requestStructure.ifBlank { "-" },
                uiMode = uiMode
            )
            DetailSection(
                title = stringResource(R.string.api_log_response),
                value = record.responseSummary.ifBlank { "-" },
                uiMode = uiMode
            )
            DetailSection(
                title = stringResource(R.string.api_log_error),
                value = record.errorSummary.ifBlank { "-" },
                uiMode = uiMode,
                error = record.errorSummary.isNotBlank()
            )
        }
    }
}

@Composable
private fun DetailsHeader(
    endpoint: String,
    meta: String,
    uiMode: BibiUiMode
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = endpoint,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                MiuixText(
                    text = meta,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    value: String,
    uiMode: BibiUiMode,
    error: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ApiLogText(
            text = title,
            uiMode = uiMode,
            strong = true,
            maxLines = 1
        )
        when (uiMode) {
            BibiUiMode.Material -> Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )

            BibiUiMode.Miuix -> MiuixText(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
