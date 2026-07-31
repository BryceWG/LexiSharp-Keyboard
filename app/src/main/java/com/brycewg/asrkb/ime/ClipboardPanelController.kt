package com.brycewg.asrkb.ime

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.DownloadStatus
import com.brycewg.asrkb.clipboard.EntryType
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ClipboardPanelController(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val views: ImeViewRefs,
    private val themeStyler: ImeThemeStyler,
    private val performKeyHaptic: (View?) -> Unit,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val showPopupMenuKeepingIme: (PopupMenu) -> Unit,
    private val onOpenFile: (filePath: String) -> Unit,
    private val onDownloadFile: (entry: ClipboardHistoryStore.Entry) -> Unit
) {
    val store: ClipboardHistoryStore = ClipboardHistoryStore(context, prefs)

    var isVisible: Boolean = views.layoutClipboardPanel?.visibility == View.VISIBLE
        private set

    private var adapter: ClipboardPanelAdapter? = null
    private var refreshGeneration: Long = 0

    fun bindListeners() {
        views.clipBtnBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hide()
        }
        views.clipBtnDelete?.setOnClickListener { v ->
            performKeyHaptic(v)
            showDeleteMenu()
        }
    }

    fun show() {
        if (isVisible) return
        val mainHeight = views.layoutMainKeyboard?.height

        views.layoutMainKeyboard?.visibility = View.GONE
        views.groupMicStatus?.visibility = View.GONE

        ensureListInit()
        refreshList()

        val panel = views.layoutClipboardPanel
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
    }

    fun hide() {
        if (!isVisible) return
        views.layoutClipboardPanel?.visibility = View.GONE
        val clipboardPanel = views.layoutClipboardPanel
        val lp = clipboardPanel?.layoutParams
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            clipboardPanel.layoutParams = lp
        }
        views.layoutMainKeyboard?.visibility = View.VISIBLE
        views.groupMicStatus?.visibility = View.VISIBLE
        isVisible = false
    }

    fun refreshList() {
        val generation = ++refreshGeneration
        serviceScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                var fileSeen = false
                store.getAll().filter { entry ->
                    if (entry.type == EntryType.TEXT) {
                        true
                    } else if (!fileSeen) {
                        fileSeen = true
                        true
                    } else {
                        false
                    }
                }
            }
            if (generation == refreshGeneration) {
                adapter?.submitList(filtered)
                views.clipTxtCount?.text = context.getString(R.string.clip_count_format, filtered.size)
            }
        }
    }

    fun onPulledFileMaybeRefresh() {
        if (isVisible) refreshList()
    }

    private fun ensureListInit() {
        if (adapter != null) return

        adapter = ClipboardPanelAdapter { e ->
            performKeyHaptic(views.clipList)
            when (e.type) {
                EntryType.TEXT -> {
                    store.pasteInto(inputConnectionProvider(), e.text)
                    hide()
                }
                EntryType.IMAGE, EntryType.FILE -> {
                    if (e.downloadStatus == DownloadStatus.COMPLETED && e.localFilePath != null) {
                        onOpenFile(e.localFilePath)
                    } else {
                        onDownloadFile(e)
                    }
                }
            }
        }

        views.clipList?.layoutManager = LinearLayoutManager(context)
        views.clipList?.adapter = adapter
        views.clipList?.addItemDecoration(ClipboardItemSpacingDecoration(context))

        val callback = ClipboardSwipeActionCallback(
            context = context,
            entryAt = { pos -> adapter?.currentList?.getOrNull(pos) },
            onThresholdReached = { v -> performKeyHaptic(v) },
            onSwiped = { pos, direction -> handleSwipeAction(pos, direction) }
        )
        ItemTouchHelper(callback).attachToRecyclerView(views.clipList)
    }

    private fun handleSwipeAction(pos: Int, direction: Int) {
        val item = adapter?.currentList?.getOrNull(pos) ?: run {
            refreshList()
            return
        }
        if (direction == ItemTouchHelper.RIGHT) {
            val pinnedNow = store.togglePin(item.id)
            val msg = if (pinnedNow) {
                context.getString(R.string.clip_pinned)
            } else {
                context.getString(R.string.clip_unpinned)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else if (direction == ItemTouchHelper.LEFT) {
            if (item.pinned) {
                Toast.makeText(
                    context,
                    context.getString(R.string.clip_cannot_delete_pinned),
                    Toast.LENGTH_SHORT
                ).show()
                adapter?.notifyItemChanged(pos)
            } else {
                val deleted = store.deleteHistoryById(item.id)
                if (deleted) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.clip_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        refreshList()
    }

    private fun showDeleteMenu() {
        val anchor = views.clipBtnDelete ?: return
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 0, 0, context.getString(R.string.clip_delete_before_1h))
        popup.menu.add(0, 1, 1, context.getString(R.string.clip_delete_before_24h))
        popup.menu.add(0, 2, 2, context.getString(R.string.clip_delete_before_7d))
        popup.menu.add(0, 3, 3, context.getString(R.string.clip_delete_all_non_pinned))
        popup.setOnMenuItemClickListener { mi ->
            val now = System.currentTimeMillis()
            val oneHour = 60 * 60 * 1000L
            val day = 24 * oneHour
            val week = 7 * day
            when (mi.itemId) {
                0 -> store.deleteHistoryBefore(now - oneHour)
                1 -> store.deleteHistoryBefore(now - day)
                2 -> store.deleteHistoryBefore(now - week)
                3 -> store.clearAllNonPinned()
            }
            refreshList()
            true
        }
        showPopupMenuKeepingIme(popup)
    }
}

/** 剪贴板列表项垂直间距，避免 Miuix 大圆角条目贴合时出现锯齿空隙。 */
private class ClipboardItemSpacingDecoration(context: Context) : RecyclerView.ItemDecoration() {
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
