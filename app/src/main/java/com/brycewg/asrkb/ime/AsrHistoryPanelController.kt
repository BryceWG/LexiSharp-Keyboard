/**
 * IME 识别历史覆盖面板：展示最终提交文本，点击粘贴后返回主键盘。
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private var adapter: AsrHistoryPanelAdapter? = null
    private var refreshGeneration: Long = 0

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

    fun refreshList() {
        val generation = ++refreshGeneration
        serviceScope.launch {
            val records = withContext(Dispatchers.Default) {
                try {
                    AsrHistoryStore(context).listRecent(MAX_VISIBLE_RECORDS)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load ASR history", t)
                    emptyList()
                }
            }
            if (generation != refreshGeneration) return@launch
            adapter?.submitList(records)
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
