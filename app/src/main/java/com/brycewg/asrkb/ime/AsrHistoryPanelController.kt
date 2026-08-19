/**
 * IME 识别历史覆盖面板：展示最终提交文本与失败记录，点击粘贴后返回主键盘。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.BibiViewThemes
import com.brycewg.asrkb.ui.history.AsrHistoryFailDisplay
import com.brycewg.asrkb.ui.history.AsrHistoryRerunCoordinator
import com.brycewg.asrkb.ui.history.AsrHistoryRerunErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AsrHistoryPanelController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val views: ImeViewRefs,
    private val themeStyler: ImeThemeStyler,
    private val performKeyHaptic: (View?) -> Unit,
    private val inputConnectionProvider: () -> InputConnection?
) {
    companion object {
        private const val TAG = "AsrHistoryPanel"
        private const val MAX_VISIBLE_RECORDS = 50
    }

    var isVisible: Boolean = views.layoutAsrHistoryPanel?.visibility == View.VISIBLE
        private set

    private val prefs = Prefs(context)
    private val audioStore = AsrHistoryAudioStore(context)
    private val rerunCoordinator = AsrHistoryRerunCoordinator(context, serviceScope)
    private var adapter: AsrHistoryPanelAdapter? = null
    private var refreshGeneration: Long = 0
    private var audioIds: Set<String> = emptySet()
    private var rerunJob: Job? = null

    fun bindListeners() {
        views.asrHistBtnBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hide()
        }
    }

    fun show() {
        if (isVisible) return
        val mainHeight = views.layoutMainKeyboard?.height

        views.layoutMainKeyboard?.visibility = View.GONE
        views.groupMicStatus?.visibility = View.GONE

        ensureListInit()
        refreshList()

        val panel = views.layoutAsrHistoryPanel
        if (panel != null) {
            themeStyler.applyKeyboardBackgroundColor(panel)
            panel.visibility = View.VISIBLE
            if (mainHeight != null && mainHeight > 0) {
                val lp = panel.layoutParams
                lp.height = mainHeight
                panel.layoutParams = lp
            }
        }
        isVisible = true
        logDiag("asr_history_show")
    }

    fun hide() {
        if (!isVisible) return
        rerunJob?.cancel()
        rerunJob = null
        adapter?.clearOverlay()
        views.layoutAsrHistoryPanel?.visibility = View.GONE
        val historyPanel = views.layoutAsrHistoryPanel
        val lp = historyPanel?.layoutParams
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            historyPanel.layoutParams = lp
        }
        views.layoutMainKeyboard?.visibility = View.VISIBLE
        views.groupMicStatus?.visibility = View.VISIBLE
        isVisible = false
    }

    fun refreshList(onListCommitted: (() -> Unit)? = null) {
        val generation = ++refreshGeneration
        serviceScope.launch {
            val (records, ids) = withContext(Dispatchers.Default) {
                val loaded = try {
                    AsrHistoryStore(context).listRecent(MAX_VISIBLE_RECORDS)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load ASR history", t)
                    emptyList()
                }
                val audioIdSet = loaded.asSequence()
                    .filter { audioStore.hasAudio(it.id) }
                    .map { it.id }
                    .toSet()
                loaded to audioIdSet
            }
            if (generation != refreshGeneration) return@launch
            audioIds = ids
            adapter?.submitList(records) {
                onListCommitted?.invoke()
            }
            views.asrHistTxtCount?.text = context.getString(R.string.clip_count_format, records.size)
            val empty = records.isEmpty()
            views.asrHistEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
            views.asrHistList?.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun ensureListInit() {
        if (adapter != null) return

        adapter = AsrHistoryPanelAdapter { record ->
            performKeyHaptic(views.asrHistList)
            pasteAndClose(record.text)
        }

        views.asrHistList?.layoutManager = LinearLayoutManager(context)
        views.asrHistList?.adapter = adapter
        views.asrHistList?.addItemDecoration(AsrHistoryItemSpacingDecoration(context))

        val theme = BibiViewThemes.resolve(context, prefs)
        val callback = ImePanelSwipeActionCallback(
            context = context,
            swipeDirsAt = { pos -> swipeDirsFor(pos) },
            revealAt = { _, dX ->
                when {
                    dX > 0f -> ImePanelSwipeReveal(
                        backgroundColor = theme.success,
                        label = context.getString(R.string.btn_reprocess)
                    )
                    dX < 0f -> ImePanelSwipeReveal(
                        backgroundColor = theme.primary,
                        label = context.getString(R.string.btn_rerecognize)
                    )
                    else -> null
                }
            },
            onThresholdReached = { v -> performKeyHaptic(v) },
            onSwiped = { pos, direction -> handleSwipeAction(pos, direction) }
        )
        ItemTouchHelper(callback).attachToRecyclerView(views.asrHistList)
    }

    private fun swipeDirsFor(pos: Int): Int {
        if (rerunJob?.isActive == true) return 0
        val record = adapter?.currentList?.getOrNull(pos) ?: return 0
        var dirs = 0
        if (record.id in audioIds) {
            dirs = dirs or ItemTouchHelper.LEFT
        }
        if (canReprocess(record)) {
            dirs = dirs or ItemTouchHelper.RIGHT
        }
        return dirs
    }

    private fun canReprocess(record: AsrHistoryStore.AsrHistoryRecord): Boolean {
        return prefs.hasLlmKeys() && AsrHistoryFailDisplay.copyText(record) != null
    }

    private fun handleSwipeAction(pos: Int, direction: Int) {
        val record = adapter?.currentList?.getOrNull(pos) ?: run {
            refreshList()
            return
        }
        if (rerunJob?.isActive == true) {
            adapter?.notifyItemChanged(pos)
            return
        }
        when (direction) {
            ItemTouchHelper.LEFT -> startRerun(record, reRecognize = true)
            ItemTouchHelper.RIGHT -> startRerun(record, reRecognize = false)
            else -> refreshList()
        }
    }

    private fun startRerun(record: AsrHistoryStore.AsrHistoryRecord, reRecognize: Boolean) {
        val event = if (reRecognize) {
            "asr_history_swipe_rerecognize"
        } else {
            "asr_history_swipe_reprocess"
        }
        val recordId = record.id
        adapter?.setOverlay(
            id = recordId,
            text = context.getString(R.string.history_rerun_working),
            error = false
        )
        rerunJob = serviceScope.launch {
            try {
                if (reRecognize) {
                    rerunCoordinator.reRecognize(record)
                } else {
                    rerunCoordinator.reprocess(record)
                }
                logDiag(event, mapOf("ok" to true))
                refreshList { adapter?.clearOverlay() }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                val reason = t.message?.trim().orEmpty().ifBlank { "rerun_failed" }
                logDiag(event, mapOf("ok" to false, "reason" to reason))
                adapter?.setOverlay(
                    id = recordId,
                    text = AsrHistoryRerunErrorMessages.format(context, reason),
                    error = true
                )
            }
        }
    }

    private fun pasteAndClose(text: String) {
        if (text.isBlank()) return
        val ic = inputConnectionProvider()
        if (ic == null) {
            Log.w(TAG, "paste failed: InputConnection is null")
            return
        }
        try {
            ic.commitText(text, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "paste failed", t)
            return
        }
        hide()
        logDiag("asr_history_paste")
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logBase("ime", event, data)
    }
}

private class AsrHistoryItemSpacingDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val gapPx = (6f * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position > 0) {
            outRect.top = gapPx
        }
    }
}
